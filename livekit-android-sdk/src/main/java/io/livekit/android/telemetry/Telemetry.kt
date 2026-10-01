/*
 * Copyright 2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.telemetry

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import io.livekit.android.Version
import io.livekit.android.events.DisconnectReason
import io.livekit.android.room.track.Track
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import io.livekit.android.util.executeAsync
import io.livekit.uniffi.TelemetryScope
import io.livekit.uniffi.TelemetrySpan
import io.livekit.uniffi.telemetryConfigure
import io.livekit.uniffi.telemetryDeviceEvent
import io.livekit.uniffi.telemetryDisable
import io.livekit.uniffi.telemetryDisconnectReason
import io.livekit.uniffi.telemetryLog
import io.livekit.uniffi.telemetryScope
import kotlinx.coroutines.flow.MutableStateFlow
import livekit.LivekitModels
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.livekit_telemetry.DeviceEvent
import uniffi.livekit_telemetry.ExportException
import uniffi.livekit_telemetry.ExportRequest
import uniffi.livekit_telemetry.ExportResponse
import uniffi.livekit_telemetry.LogRecord
import uniffi.livekit_telemetry.LogSource
import uniffi.livekit_telemetry.Sdk
import uniffi.livekit_telemetry.Severity
import uniffi.livekit_telemetry.SpanTrack
import uniffi.livekit_telemetry.TelemetryConfig
import uniffi.livekit_telemetry.TelemetryResource
import uniffi.livekit_telemetry.TelemetryTransport
import uniffi.livekit_telemetry.TrackKind
import uniffi.livekit_telemetry.TrackSource
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import uniffi.livekit_telemetry.DisconnectReason as FfiDisconnectReason

/**
 * Client telemetry. The pipeline — destination, token, batching, retries, cache, holds, stats
 * mapping, span state — lives in the Rust core, one per process; Android installs it with the
 * first Room, feeds it OS signals and moves its bytes. Every tuning value is the core's default.
 */
@PublishedApi
internal object Telemetry {
    /** Null until the first Room installs the pipeline; false when it could not start. */
    @Volatile
    @VisibleForTesting
    internal var installed: Boolean? = null

    /** Flips once, at the opt-out; a flow so a connected Room's RTC instrument stops right away. */
    val optedOut = MutableStateFlow(false)

    @VisibleForTesting
    internal var disabled: Boolean
        get() = optedOut.value
        set(value) {
            optedOut.value = value
        }

    /**
     * The span the current coroutine works inside: child spans nest under it and warn/error
     * records point at it. Bound with `asContextElement` around connect, a reconnect cycle and
     * publish.
     */
    val currentSpan = ThreadLocal<TelemetrySpan?>()

    /**
     * The Room the current coroutine works for: bound on the Room's, the engine's and the signal
     * client's coroutines, so a Room handler's warning lands in that Room's trace.
     */
    val currentScope = ThreadLocal<TelemetryScope?>()

    /** A new Room's scope, installing the pipeline first if this is the first Room; null when off. */
    fun scope(context: Context): TelemetryScope? {
        if (disabled) {
            // A previous launch's cache: the core can only purge a pipeline it has, and none installs now.
            runCatching { storageDirectory(context).deleteRecursively() }
            return null
        }
        if (installed == null) {
            synchronized(this) {
                if (installed == null) configure(context)
            }
        }
        return if (active) guarded { telemetryScope() } else null
    }

    /**
     * Install (or replace) the process pipeline; refused by the core after an opt-out. Fail-open, a
     * missing native library included: the app runs without telemetry rather than not at all.
     */
    fun configure(context: Context): Boolean {
        installed = install(context.applicationContext)
        return installed == true
    }

    private fun install(context: Context): Boolean = try {
        val sdk = TelemetryResource(
            sdk = Sdk.ANDROID,
            sdkVersion = Version.CLIENT_VERSION,
            osName = "android",
            osVersion = Build.VERSION.RELEASE ?: "",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        )
        val config = TelemetryConfig(sdk = sdk, storageDir = storageDirectory(context).path)
        telemetryConfigure(config, OkHttpTelemetryTransport(), listOf(DeviceTelemetry(context)))
        true
    } catch (e: Throwable) {
        diagnose(e, "Telemetry could not start; running without it.")
        false
    }

    /** The on-disk batch cache: the app's cache directory, which the OS may clear and backups skip. */
    fun storageDirectory(context: Context) = File(context.cacheDir, "livekit-telemetry")

    /**
     * The process opt-out, in effect when this returns: the core stops capturing and purges what
     * it has not sent. [disabled] stays for [scope], which deletes a previous launch's cache
     * without installing a pipeline (the core purges only one it has).
     */
    fun disable() {
        synchronized(this) { disabled = true } // after this, ifCollecting starts nothing
        try {
            telemetryDisable()
        } catch (e: Throwable) { // a missing native library included: the opt-out never fails the app
            diagnose(e, "The opt-out did not reach the core; Rooms created from now on still collect nothing.")
        }
    }

    fun deviceEvent(event: DeviceEvent) {
        if (active) guarded { telemetryDeviceEvent(event) }
    }

    /**
     * Runs [collect] only while telemetry is on, under the lock [disable] sets the flag with: no
     * collection begins once it has returned. [collect] must only start work, never wait for it.
     */
    fun <T> ifCollecting(collect: () -> T): T? = synchronized(this) { if (disabled) null else collect() }

    /** Installed and not opted out: the only state in which anything reaches the core. */
    private val active get() = installed == true && !disabled

    /** Whether [LKLog] hands records at [level] to telemetry, whatever the console level: the core's floor. */
    @PublishedApi
    internal fun captures(level: LoggingLevel): Boolean =
        active && level >= LoggingLevel.WARN && level != LoggingLevel.OFF

    /**
     * An SDK warning or error, filed under the ambient span, else the ambient Room, else the
     * process. Telemetry's own lines never feed back into the pipeline.
     */
    @PublishedApi
    internal fun log(level: LoggingLevel, t: Throwable?, message: String) {
        if (!captures(level)) return
        guarded {
            val caller = Throwable("caller").stackTrace.firstOrNull { frame ->
                !frame.className.startsWith(LKLog::class.java.name) && !frame.className.startsWith(Telemetry::class.java.name)
            }
            if (caller?.className?.startsWith(OWN_PACKAGE) == true) return
            val span = currentSpan.get()?.takeIf { !it.isEnded() }
            val record = LogRecord(
                severity = if (level == LoggingLevel.WARN) Severity.WARN else Severity.ERROR,
                source = LogSource.SDK,
                body = listOfNotNull(message.takeIf { it.isNotEmpty() }, t?.toString()).joinToString(": "),
                logger = caller?.className?.substringAfterLast('.')?.substringBefore('$'),
                function = caller?.methodName,
                file = caller?.fileName,
                line = caller?.lineNumber?.takeIf { it > 0 }?.toUInt(),
                spanId = span?.context()?.spanId,
            )
            val scope = currentScope.get()
            if (span == null && scope != null) scope.log(record) else telemetryLog(record)
        }
    }

    /** WebRTC's own errors, next to (never instead of) the app's console logger. */
    fun logWebRtc(tag: String, message: String) {
        if (active) guarded { telemetryLog(LogRecord(Severity.ERROR, LogSource.WEB_RTC, message.trim(), logger = tag)) }
    }

    private const val OWN_PACKAGE = "io.livekit.android.telemetry."
}

// MARK: - Transport

/**
 * Moves the core's requests: status, headers and body go back untouched and the core decides
 * what they mean. Only a missing answer is an error. Follows no redirect — the request carries
 * the participant token — so a 3xx comes back as the answer.
 */
@Suppress("SwallowedException") // the core takes a reason, not a cause
internal class OkHttpTelemetryTransport(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
) : TelemetryTransport {
    override suspend fun send(request: ExportRequest): ExportResponse {
        val httpRequest = try {
            Request.Builder()
                .url(request.url)
                .post(request.body.toRequestBody())
                .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                .build()
        } catch (e: IllegalArgumentException) {
            throw ExportException.Rejected("invalid request: ${e.message}")
        }
        val response = try {
            client.newCall(httpRequest).executeAsync()
        } catch (e: IOException) {
            throw ExportException.Retryable(e.toString(), null)
        }
        return response.use {
            ExportResponse(it.code.toUShort(), it.headers.toMap(), it.body?.bytes() ?: ByteArray(0))
        }
    }
}

// MARK: - Shared vocabulary

/**
 * A telemetry-only core call, which must never become the caller's failure (a core panic surfaces
 * as an exception): reported with [diagnose] and swallowed.
 */
internal inline fun <T> guarded(call: () -> T): T? = try {
    call()
} catch (e: Throwable) {
    diagnose(e, "Telemetry call failed; ignored.")
    null
}

/**
 * Best effort: a console line about telemetry's own failure, never the caller's failure, even when
 * the app's logger throws. Not through [LKLog.log], which would feed it back into the core.
 */
internal fun diagnose(e: Throwable, message: String) {
    runCatching { if (LoggingLevel.WARN >= LKLog.loggingLevel) LKLog.logger?.log(LoggingLevel.WARN, e, message) }
}

/** End on an exception: `cancelled` for a cancellation, `error` otherwise. */
internal fun TelemetrySpan.end(error: Throwable) {
    if (error is CancellationException) cancel() else fail(error.errorType())
}

/** `error.type`: the exception's class name. */
internal fun Throwable.errorType(): String = javaClass.simpleName.ifEmpty { javaClass.name }

internal fun spanTrack(kind: Track.Kind, source: Track.Source, sid: String? = null, remoteIdentity: String? = null): SpanTrack? {
    val trackKind = when (kind) {
        Track.Kind.AUDIO -> TrackKind.AUDIO
        Track.Kind.VIDEO -> TrackKind.VIDEO
        Track.Kind.UNRECOGNIZED -> return null
    }
    val trackSource = when (source) {
        Track.Source.CAMERA -> TrackSource.CAMERA
        Track.Source.MICROPHONE -> TrackSource.MICROPHONE
        Track.Source.SCREEN_SHARE -> TrackSource.SCREEN_SHARE
        Track.Source.SCREEN_SHARE_AUDIO -> TrackSource.SCREEN_SHARE_AUDIO
        Track.Source.UNKNOWN -> TrackSource.UNKNOWN
    }
    return SpanTrack(sid, trackKind, trackSource, remoteIdentity)
}

/** The shared enum by the protocol's number; the SDK enum mirrors the protocol's names. */
internal val DisconnectReason.telemetry: FfiDisconnectReason
    get() = telemetryDisconnectReason(runCatching { LivekitModels.DisconnectReason.valueOf(name).number }.getOrDefault(0))
