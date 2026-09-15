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

import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.uniffi.TelemetryScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.RTCStatsReport
import uniffi.livekit_telemetry.AttributeValue
import uniffi.livekit_telemetry.RtcStat
import uniffi.livekit_telemetry.StreamDirection
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * The RTC-area instrument of one Room. Once a second it hands the raw `getStats()` report of
 * every track the Room publishes or subscribes to the Room's scope — the core maps and windows
 * it into `lk.rtc.stats.sample` — and it reports the remote tracks' lifecycle so the core can run
 * the `lk.subscribe` span (intent → first media, ended by the first inbound reading with bytes).
 * No RTC state lives here.
 */
internal class RTCTelemetry(private val room: Room, private val scope: TelemetryScope) {
    private class Observed(val track: Track, val direction: StreamDirection)

    /** Published and subscribed tracks by sid. */
    private val observed = ConcurrentHashMap<String, Observed>()

    /** Runs for the Room's connected lifetime; cancelled with the Room's scope. */
    suspend fun run() = coroutineScope {
        launch { room.events.events.collect { onEvent(it) } }
        try {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                for ((sid, entry) in observed) {
                    val kind = entry.track.kind.telemetry ?: continue
                    val report = entry.track.getRTCStats() ?: continue
                    scope.recordStatsReport(sid, kind, entry.direction, report.telemetryStats, report.telemetryTimestampNs)
                }
            }
        } finally {
            observed.clear()
        }
    }

    private fun onEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.TrackPublished -> when (val participant = event.participant) {
                is LocalParticipant -> event.publication.track?.let { observed[event.publication.sid] = Observed(it, StreamDirection.OUTBOUND) }
                // With autoSubscribe the intent exists the moment the track is known.
                is RemoteParticipant -> if ((event.publication as? RemoteTrackPublication)?.isDesired == true) {
                    spanTrack(event.publication, participant)?.let(scope::subscribeStarted)
                }
            }

            is RoomEvent.TrackUnpublished -> {
                observed.remove(event.publication.sid)
                if (event.participant is RemoteParticipant) scope.subscribeCancelled(event.publication.sid)
            }

            is RoomEvent.TrackSubscribed -> {
                spanTrack(event.publication, event.participant)?.let(scope::subscribed)
                observed[event.publication.sid] = Observed(event.track, StreamDirection.INBOUND)
            }

            is RoomEvent.TrackUnsubscribed -> {
                observed.remove(event.publications.sid)
                scope.subscribeCancelled(event.publications.sid)
            }

            is RoomEvent.TrackSubscriptionFailed -> scope.subscribeFailed(event.sid, event.exception.errorType())
            else -> {}
        }
    }

    private fun spanTrack(publication: TrackPublication, participant: RemoteParticipant) =
        spanTrack(publication.kind, publication.source, publication.sid, participant.identity?.value)

    companion object {
        /** The core windows 1 Hz readings into `statsWindow` samples. */
        private const val STATS_INTERVAL_MS = 1000L
    }
}

/**
 * The report as the core takes it: every entry with its standard members, nested maps flattened
 * with a dot (`qualityLimitationDurations.cpu`). No field names known here.
 */
internal val RTCStatsReport.telemetryStats: List<RtcStat>
    get() = statsMap.values.map { stat ->
        RtcStat(kind = stat.type, id = stat.id, members = buildMap { flatten(stat.members, "", this) })
    }

internal val RTCStatsReport.telemetryTimestampNs: ULong
    get() = (timestampUs.coerceAtLeast(0.0) * 1000).toULong()

private fun flatten(values: Map<*, *>, prefix: String, into: MutableMap<String, AttributeValue>) {
    for ((key, value) in values) {
        val name = if (prefix.isEmpty()) key.toString() else "$prefix.$key"
        when (value) {
            is Boolean -> into[name] = AttributeValue.Bool(value)
            is Int, is Long, is Short, is Byte, is BigInteger -> into[name] = AttributeValue.Int((value as Number).toLong())
            is Number -> into[name] = AttributeValue.Double(value.toDouble())
            is String -> into[name] = AttributeValue.Str(value)
            is Map<*, *> -> flatten(value, name, into)
            else -> {} // sequences carry nothing the core reads
        }
    }
}
