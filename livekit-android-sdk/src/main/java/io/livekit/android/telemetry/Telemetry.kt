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
import io.livekit.android.Version
import io.livekit.android.events.DisconnectReason
import io.livekit.android.room.track.Track
import io.livekit.android.telemetry.TelemetryOptions.Instrument
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import io.livekit.android.util.executeAsync
import io.livekit.uniffi.LogForwardFilter
import io.livekit.uniffi.LogForwardLevel
import io.livekit.uniffi.TelemetryScope
import io.livekit.uniffi.TelemetrySpan
import io.livekit.uniffi.logForwardBootstrap
import io.livekit.uniffi.logForwardReceive
import io.livekit.uniffi.telemetryConfigure
import io.livekit.uniffi.telemetryDeviceEvent
import io.livekit.uniffi.telemetryDiagnostics
import io.livekit.uniffi.telemetryDisconnectReason
import io.livekit.uniffi.telemetryLog
import io.livekit.uniffi.telemetryScope
import io.livekit.uniffi.telemetrySetAttribute
import io.livekit.uniffi.telemetrySetServer
import io.livekit.uniffi.telemetryShutdown
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import livekit.LivekitModels
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.livekit_telemetry.Attribute
import uniffi.livekit_telemetry.AttributeValue
import uniffi.livekit_telemetry.DeviceEvent
import uniffi.livekit_telemetry.ExportException
import uniffi.livekit_telemetry.ExportRequest
import uniffi.livekit_telemetry.ExportResponse
import uniffi.livekit_telemetry.LogRecord
import uniffi.livekit_telemetry.LogSource
import uniffi.livekit_telemetry.Sdk
import uniffi.livekit_telemetry.Severity
import uniffi.livekit_telemetry.SpanName
import uniffi.livekit_telemetry.SpanOutcome
import uniffi.livekit_telemetry.SpanTrack
import uniffi.livekit_telemetry.TelemetryConfig
import uniffi.livekit_telemetry.TelemetryInstrument
import uniffi.livekit_telemetry.TelemetryResource
import uniffi.livekit_telemetry.TelemetryTransport
import uniffi.livekit_telemetry.TrackKind
import uniffi.livekit_telemetry.TrackSource
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import uniffi.livekit_telemetry.DisconnectReason as FfiDisconnectReason
import uniffi.livekit_telemetry.Instrument as FfiInstrument

/**
 * Client telemetry. The pipeline lives in the Rust core, one per process like a logger, and so do
 * the instruments it runs: Android only builds the platform ones and hands them over. Configure
 * with [io.livekit.android.LiveKit.setTelemetry] before creating Rooms, like the logger.
 */
object Telemetry {
    /** What was configured: the instruments a Room starts (`room`, `rtc`) and the log gate. */
    @PublishedApi
    @Volatile
    internal var options: TelemetryOptions? = null

    /**
     * The span the current coroutine is working inside, if any. Bound by the SDK around operations
     * (connect, a reconnect cycle) with `asContextElement`, so warn/error records point at it
     * without any handle being passed around; does not cross the WebRTC callback boundary.
     */
    internal val currentSpan = ThreadLocal<TelemetrySpan?>()

    /**
     * The Room's scope the current coroutine works for, if any: bound on the Room's, the engine's
     * and the signal client's coroutine scopes, so a warn/error record from a Room handler is
     * filed under that Room's session even with no span in flight.
     */
    internal val currentScope = ThreadLocal<TelemetryScope?>()

    private var coreLogs: Job? = null

    /**
     * Set or change the options. The pipeline starts now, so pre-connect errors are captured; its
     * destination waits for the first connect unless the options name an endpoint. Fail-open: the
     * app runs without telemetry rather than not at all.
     */
    fun configure(appContext: Context, options: TelemetryOptions) {
        val context = appContext.applicationContext
        this.options = options
        try {
            val instruments = buildList<TelemetryInstrument> {
                if (Instrument.DEVICE in options.instruments) add(DeviceTelemetry(context))
            }
            telemetryConfigure(options.toConfig(context), OkHttpTelemetryTransport(), instruments)
            forwardCoreLogs()
        } catch (e: Throwable) {
            this.options = null
            LKLog.w(e) { "Telemetry could not start; running without it." }
        }
    }

    /** Turn telemetry off after a bounded final flush. */
    suspend fun shutdown() {
        if (options == null) return
        options = null
        telemetryShutdown()
    }

    /**
     * Attach an attribute to every record of every scope — an `enduser.id`, a tenant, a build
     * flavor. Strings, numbers and booleans keep their type; `null` removes the attribute.
     */
    fun setAttribute(key: String, value: Any?) {
        if (options != null) telemetrySetAttribute(key, value?.lowered())
    }

    /**
     * A one-line readout of the pipeline's health for a debug console: status, throughput,
     * backlog and losses.
     */
    fun diagnostics(): String = if (options != null) telemetryDiagnostics() else "telemetry off"

    /** A Room's scope on the process pipeline; null when telemetry is off (the core is not touched then). */
    internal fun scope(): TelemetryScope? = if (options != null) telemetryScope() else null

    internal fun enabled(instrument: Instrument): Boolean = options?.instruments?.contains(instrument) == true

    internal fun setServer(url: String, token: String) {
        if (options != null) telemetrySetServer(url, token)
    }

    internal fun deviceEvent(event: DeviceEvent) {
        if (enabled(Instrument.DEVICE)) telemetryDeviceEvent(event)
    }

    /** Whether [LKLog] hands records at [level] to telemetry, whatever the console level. */
    @PublishedApi
    internal fun captures(level: LoggingLevel): Boolean {
        val options = options ?: return false
        return Instrument.LOGS in options.instruments && level != LoggingLevel.OFF && level >= options.logLevel
    }

    /**
     * A warn/error record from the SDK logger; the core files it under the ambient span's scope,
     * else the ambient Room's, else the process. Telemetry's own lines never feed back into the
     * pipeline.
     */
    @PublishedApi
    internal fun log(level: LoggingLevel, t: Throwable?, message: String) {
        if (!captures(level)) return
        val caller = Throwable().stackTrace.firstOrNull { frame ->
            !frame.className.startsWith(LKLog::class.java.name) && !frame.className.startsWith(Telemetry::class.java.name)
        }
        if (caller?.className?.startsWith(OWN_PACKAGE) == true) return
        val span = currentSpan.get()
        val record = LogRecord(
            severity = level.severity,
            source = LogSource.SDK,
            message = listOfNotNull(message.takeIf { it.isNotEmpty() }, t?.toString()).joinToString(": "),
            logger = caller?.className?.substringAfterLast('.')?.substringBefore('$'),
            function = caller?.methodName,
            file = caller?.fileName,
            line = caller?.lineNumber?.takeIf { it > 0 }?.toUInt(),
            spanId = span?.context()?.spanId,
        )
        val scope = currentScope.get()
        if (span == null && scope != null) scope.log(record) else telemetryLog(record)
    }

    /** WebRTC's native log lines; the core only lets `error` leave the device. */
    internal fun logWebRtc(tag: String, message: String) {
        if (enabled(Instrument.LOGS)) telemetryLog(LogRecord(Severity.ERROR, LogSource.WEB_RTC, message, logger = tag))
    }

    /**
     * The Rust core's log lines, into the SDK logger like the SDK's own (the pipeline reports its
     * health at debug and prints the `describe()` lines there) and, at warn and above, into the
     * pipeline as `ffi` records.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun forwardCoreLogs() {
        if (coreLogs != null) return
        logForwardBootstrap(LogForwardFilter.DEBUG)
        coreLogs = GlobalScope.launch(Dispatchers.Default) {
            while (true) {
                val entry = logForwardReceive() ?: break
                val level = entry.level.loggingLevel
                if (level >= LKLog.loggingLevel) LKLog.logger?.log(level, null, "${entry.target}: ${entry.message}")
                if (level >= LoggingLevel.WARN && enabled(Instrument.LOGS)) {
                    telemetryLog(LogRecord(level.severity, LogSource.FFI, entry.message, logger = entry.target, file = entry.file, line = entry.line))
                }
            }
        }
    }

    private const val OWN_PACKAGE = "io.livekit.android.telemetry."
}

// MARK: - Transport

/**
 * The host's half of the pipeline: a dumb bytes mover. The core composed URL, headers and body;
 * this only performs the POST and hands back whatever came back, so retry / drop / go-silent is
 * decided the same way on every platform. Only a missing response is an error.
 */
internal class OkHttpTelemetryTransport(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
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

// MARK: - Options lowering

/**
 * The core's record: what the app chose, plus the platform's part (who is reporting, where the
 * cache lives).
 */
internal fun TelemetryOptions.toConfig(context: Context) = TelemetryConfig(
    endpoint = endpoint,
    headers = headers,
    sdk = TelemetryResource(
        sdk = Sdk.ANDROID,
        sdkVersion = Version.CLIENT_VERSION,
        osName = "android",
        osVersion = Build.VERSION.RELEASE ?: "",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    ),
    storageDir = storageDirectory?.let { if (it.isAbsolute) it else File(context.cacheDir, it.path) }?.path,
    flushIntervalMs = flushInterval.inWholeMilliseconds.coerceAtLeast(0).toULong(),
    statsWindowMs = statsWindow.inWholeMilliseconds.coerceAtLeast(0).toULong(),
    logSeverity = logLevel.severity,
    disabledInstruments = Instrument.entries.filter { it !in instruments }.map { it.ffi },
)

private val Instrument.ffi: FfiInstrument
    get() = when (this) {
        Instrument.ROOM -> FfiInstrument.ROOM
        Instrument.RTC -> FfiInstrument.RTC
        Instrument.LOGS -> FfiInstrument.LOGS
        Instrument.DEVICE -> FfiInstrument.DEVICE
    }

// MARK: - Attributes

internal fun Any.lowered(): AttributeValue = when (this) {
    is String -> AttributeValue.Str(this)
    is Boolean -> AttributeValue.Bool(this)
    is Int, is Long, is Short, is Byte -> AttributeValue.Int((this as Number).toLong())
    is Number -> AttributeValue.Double(toDouble())
    else -> AttributeValue.Str(toString())
}

internal fun Map<String, Any>.lowered(): List<Attribute> = map { (key, value) -> Attribute(key, value.lowered()) }

// MARK: - Spans

/**
 * An SDK span in this scope's trace, stamped now in the core, nested under the ambient span;
 * null when telemetry is off, so a span costs nothing there (call sites chain optionally).
 */
internal fun TelemetryScope?.begin(name: SpanName, parent: TelemetrySpan? = Telemetry.currentSpan.get()): TelemetrySpan? =
    this?.start(name, parent)

/** End successfully. */
internal fun TelemetrySpan.end() = end(SpanOutcome.OK, null)

/**
 * End on an exception: `cancelled` for a cancellation, `error` otherwise, with the exception's
 * type as the status message.
 */
internal fun TelemetrySpan.end(error: Throwable) {
    if (error is CancellationException) cancel() else fail(error.errorType())
}

/** `error.type` for a span: the exception's class name. */
internal fun Throwable.errorType(): String = javaClass.simpleName.ifEmpty { javaClass.name }

/** The track a publish or subscribe span is about; call again once the sid is known. */
internal fun TelemetrySpan.setTrack(kind: Track.Kind, source: Track.Source, sid: String? = null) {
    spanTrack(kind, source, sid)?.let(::setTrack)
}

internal fun spanTrack(kind: Track.Kind, source: Track.Source, sid: String? = null, remoteIdentity: String? = null): SpanTrack? {
    val trackKind = kind.telemetry ?: return null
    return SpanTrack(sid, trackKind, source.telemetry, remoteIdentity)
}

// MARK: - Shared vocabulary

internal val LoggingLevel.severity: Severity
    get() = when (this) {
        LoggingLevel.VERBOSE -> Severity.TRACE
        LoggingLevel.DEBUG -> Severity.DEBUG
        LoggingLevel.INFO -> Severity.INFO
        LoggingLevel.WARN -> Severity.WARN
        LoggingLevel.ERROR, LoggingLevel.WTF, LoggingLevel.OFF -> Severity.ERROR
    }

internal val LogForwardLevel.loggingLevel: LoggingLevel
    get() = when (this) {
        LogForwardLevel.ERROR -> LoggingLevel.ERROR
        LogForwardLevel.WARN -> LoggingLevel.WARN
        LogForwardLevel.INFO -> LoggingLevel.INFO
        LogForwardLevel.DEBUG -> LoggingLevel.DEBUG
        LogForwardLevel.TRACE -> LoggingLevel.VERBOSE
    }

internal val Track.Kind.telemetry: TrackKind?
    get() = when (this) {
        Track.Kind.AUDIO -> TrackKind.AUDIO
        Track.Kind.VIDEO -> TrackKind.VIDEO
        Track.Kind.UNRECOGNIZED -> null
    }

internal val Track.Source.telemetry: TrackSource
    get() = when (this) {
        Track.Source.CAMERA -> TrackSource.CAMERA
        Track.Source.MICROPHONE -> TrackSource.MICROPHONE
        Track.Source.SCREEN_SHARE -> TrackSource.SCREEN_SHARE
        Track.Source.SCREEN_SHARE_AUDIO -> TrackSource.SCREEN_SHARE_AUDIO
        Track.Source.UNKNOWN -> TrackSource.UNKNOWN
    }

/**
 * The Room's disconnect reason in the shared vocabulary: the protocol's number (the SDK enum
 * mirrors the protocol's names), or the client giving up on a reconnect.
 */
internal fun DisconnectReason.telemetry(reconnectFailed: Boolean): FfiDisconnectReason =
    if (this == DisconnectReason.UNKNOWN_REASON && reconnectFailed) {
        FfiDisconnectReason.RECONNECT_FAILED
    } else {
        telemetryDisconnectReason(LivekitModels.DisconnectReason.valueOf(name).number)
    }
