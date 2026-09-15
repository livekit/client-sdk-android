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

import io.livekit.android.util.LoggingLevel
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Client telemetry: ships SDK diagnostics (warn/error records, per-track RTC statistics, device
 * state, operation spans) out-of-band to an OTLP/HTTP collector. Process-wide: configure once with
 * [io.livekit.android.LiveKit.setTelemetry] before creating Rooms.
 */
data class TelemetryOptions(
    /**
     * Full OTLP/HTTP logs URL, e.g. `http://localhost:4318/v1/logs` for a local collector.
     * `null` (the default) derives it from the server the first Room connects to
     * (`https://<host>/observability/logs/otlp/v0`) and authenticates with the room token;
     * until then everything is buffered on device.
     */
    val endpoint: String? = null,
    /** Extra request headers, e.g. `Authorization`. */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Directory for the on-disk batch cache. A relative path is resolved against the app's cache
     * directory (the default, `livekit-telemetry`); `null` keeps batches in memory only.
     */
    val storageDirectory: File? = File("livekit-telemetry"),
    /** Export cadence. Stretched automatically under thermal / battery-saver pressure. */
    val flushInterval: Duration = 15.seconds,
    /** RTC statistics window: one `lk.rtc.stats.sample` per track per window. */
    val statsWindow: Duration = 15.seconds,
    /** Which instruments run; all by default. App-defined events and session identity are always on. */
    val instruments: Set<Instrument> = Instrument.ALL,
    /**
     * Lowest log level that leaves the device (warnings and errors by default). Events are not
     * logs and are not subject to it; WebRTC's own logs go from [LoggingLevel.ERROR] regardless.
     */
    val logLevel: LoggingLevel = LoggingLevel.WARN,
) {
    /** The telemetry instruments, by area. Combine to choose what runs. */
    enum class Instrument {
        /** Spans of the Room's operations: `lk.connect`, `lk.reconnect`, `lk.publish`. */
        ROOM,

        /** Track statistics windows and the `lk.subscribe` span (time to media). */
        RTC,

        /** Warning and error log records from the SDK, the Rust core and WebRTC. */
        LOGS,

        /** Device state — thermal, power, memory, network, battery — and audio / capture events. */
        DEVICE,
        ;

        companion object {
            val ALL: Set<Instrument> = entries.toSet()
        }
    }
}
