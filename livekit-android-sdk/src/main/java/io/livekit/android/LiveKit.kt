/*
 * Copyright 2023-2026 LiveKit, Inc.
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

package io.livekit.android

import android.app.Application
import android.content.Context
import io.livekit.android.LiveKit.loggingLevel
import io.livekit.android.dagger.DaggerLiveKitComponent
import io.livekit.android.dagger.RTCModule
import io.livekit.android.dagger.create
import io.livekit.android.room.Room
import io.livekit.android.telemetry.Telemetry
import io.livekit.android.telemetry.TelemetryOptions
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * The main entry point into using LiveKit.
 *
 * @see [LiveKit.create]
 */
object LiveKit {
    /**
     * [LoggingLevel] to use for Livekit logs. Set to [LoggingLevel.OFF] to turn off logs.
     *
     * Defaults to [LoggingLevel.OFF]
     */
    @JvmStatic
    var loggingLevel: LoggingLevel
        get() = LKLog.loggingLevel
        set(value) {
            LKLog.loggingLevel = value
        }

    /**
     * The [LKLog.Logger] to use for Livekit logs.
     *
     * Default implementation prints to logcat.
     */
    @JvmStatic
    var logger: LKLog.Logger?
        get() = LKLog.logger
        set(value) {
            LKLog.logger = value
        }

    /**
     * Enables logs for the underlying WebRTC sdk logging. Used in conjunction with [loggingLevel].
     *
     * Note: WebRTC logging is very noisy and should only be used to diagnose native WebRTC issues.
     */
    @JvmStatic
    var enableWebRTCLogging: Boolean = false

    /**
     * Turn client telemetry on: warn/error records, RTC statistics, operation spans and device
     * state, shipped out-of-band to an OTLP collector. Process-wide, like [loggingLevel]: call it
     * before creating Rooms — each Room gets its own scope (see [Room.telemetryTraceId]).
     * `null` turns telemetry off (the default) after a bounded final flush.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @JvmStatic
    fun setTelemetry(appContext: Context, options: TelemetryOptions?) {
        if (options != null) {
            Telemetry.configure(appContext, options)
        } else {
            GlobalScope.launch { Telemetry.shutdown() }
        }
    }

    /**
     * Certain WebRTC classes need to be initialized prior to use.
     *
     * This does not need to be called under normal circumstances, as [LiveKit.create]
     * will handle this for you.
     */
    fun init(appContext: Context) {
        RTCModule.libWebrtcInitialization(appContext)
    }

    /**
     * Create a Room object.
     */
    fun create(
        appContext: Context,
        options: RoomOptions = RoomOptions(),
        overrides: LiveKitOverrides = LiveKitOverrides(),
    ): Room {
        val ctx = appContext.applicationContext

        if (ctx !is Application) {
            LKLog.w { "Application context was not found, this may cause memory leaks." }
        }

        val component = DaggerLiveKitComponent
            .factory()
            .create(ctx, overrides)

        val room = component.roomFactory().create(ctx)
        room.setRoomOptions(options)

        return room
    }
}
