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

import androidx.annotation.VisibleForTesting
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.util.LKLog
import io.livekit.uniffi.TelemetryScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.RTCStatsReport
import uniffi.livekit_telemetry.AttributeValue
import uniffi.livekit_telemetry.RtcStat
import java.math.BigInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * A Room's RTC instrument. Reports the remote tracks' lifecycle, from which the core runs the
 * `lk.subscribe` span (intent → first media), and hands it one raw `getStats()` report per peer
 * connection as often as it asks; the core maps every RTP stream to its track and windows it.
 */
internal class RTCTelemetry(private val room: Room, private val scope: TelemetryScope) {
    /** Cuts the current wait short when a track appears: the core then asks for its 1 s pace. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Runs for one connection of the Room, in [connection]. Started before the Room joins, so the
     * tracks published and subscribed during the join are seen too. Cancelled at the opt-out,
     * which never waits for it; a getStats() request still on its way is refused by [Telemetry.ifCollecting].
     */
    fun start(connection: CoroutineScope) {
        // Fail-open like the rest of telemetry: a failing core call is logged, never the app's crash.
        val failOpen = CoroutineExceptionHandler { _, e -> LKLog.w(e) { "RTC telemetry stopped." } }
        // Undispatched: subscribed to the Room's events before this returns.
        val collector = connection.launch(failOpen, start = CoroutineStart.UNDISPATCHED) {
            room.events.events.takeWhile { !Telemetry.disabled }.collect { event ->
                runCatching { onEvent(event) }.onFailure { LKLog.w(it) { "RTC telemetry skipped ${event::class.simpleName}." } }
            }
        }
        // On its own clock, not the Room's dispatcher: the core paces it.

        @Suppress("InjectDispatcher")
        val poller = connection.launch(Dispatchers.Default + failOpen) {
            pollStats(interval = { scope.statsPollIntervalMs().toLong() }, wake = wake) {
                try {
                    recordPeerStats()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LKLog.w(e) { "RTC telemetry skipped a poll." }
                }
            }
        }
        connection.launch {
            Telemetry.optedOut.first { it }
            collector.cancel()
            poller.cancel()
        }
    }

    private fun onEvent(event: RoomEvent) {
        when (event) {
            // With autoSubscribe the intent exists the moment the track is known.
            is RoomEvent.TrackPublished -> {
                if ((event.publication as? RemoteTrackPublication)?.isDesired == true) {
                    spanTrack(event.publication, event.participant as RemoteParticipant)?.let(scope::subscribeStarted)
                }
                wake.trySend(Unit)
            }

            // A full reconnect announces the remote tracks again, in its own join.
            is RoomEvent.Connected, is RoomEvent.Reconnected -> reconcileJoinedTracks()

            is RoomEvent.TrackSubscribed -> {
                spanTrack(event.publication, event.participant)?.let(scope::subscribed)
                wake.trySend(Unit) // a manual subscribe starts its wait here
            }

            is RoomEvent.TrackSubscriptionFailed -> scope.subscribeFailed(event.sid, event.exception.errorType())
            is RoomEvent.TrackUnsubscribed -> scope.trackEnded(event.publications.sid)
            is RoomEvent.TrackUnpublished -> scope.trackEnded(event.publication.sid)
            else -> {}
        }
    }

    /** A manual subscribe: its intent opens `lk.subscribe`, and the poller takes up the core's faster pace at once. */
    fun subscribeIntent(publication: RemoteTrackPublication, participant: RemoteParticipant) {
        spanTrack(publication, participant)?.let(scope::subscribeStarted)
        wake.trySend(Unit)
    }

    /** The join announces tracks without a TrackPublished event: their intent starts at connect. */
    private fun reconcileJoinedTracks() {
        for (participant in room.remoteParticipants.values) {
            val pending = participant.trackPublications.values.filter { (it as? RemoteTrackPublication)?.isDesired == true && it.track == null }
            pending.forEach { publication -> spanTrack(publication, participant)?.let(scope::subscribeStarted) }
        }
        wake.trySend(Unit)
    }

    /**
     * One report per peer connection, with every track this Room sends or receives. Each getStats()
     * call and each submit runs under the opt-out's lock, so none begins once [Telemetry.disable]
     * has returned; waiting for an answer holds no lock.
     */
    @VisibleForTesting
    internal suspend fun recordPeerStats() {
        // One RTC thread hop for every id: each track's own read then runs in place.
        val tracks = room.engine.onRTCThread<Map<String, String>> {
            buildMap {
                for (participant in listOf(room.localParticipant) + room.remoteParticipants.values) {
                    for (publication in participant.trackPublications.values) {
                        publication.track?.withRTCTrack<String?>(null) { id() }?.let { put(it, publication.sid) }
                    }
                }
            }
        } ?: return
        for (report in room.engine.peerStats { request -> Telemetry.ifCollecting(request) != null }) {
            if (report.statsMap.isEmpty()) continue
            Telemetry.ifCollecting { scope.recordPeerStats(report.telemetryStats, tracks, report.telemetryTimestampNs) } ?: return
        }
    }
}

/**
 * Calls [poll] every [interval] ms, asking for the interval again after each wait. A [wake]
 * re-reads the interval (a new track shortens it) but keeps the deadline, so wakes arriving faster
 * than the interval never starve the polls.
 */
internal suspend fun pollStats(
    interval: () -> Long,
    wake: ReceiveChannel<Unit>,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    poll: suspend () -> Unit,
) = coroutineScope {
    var polledAt = now()
    while (isActive) {
        val due = polledAt + interval() - now()
        if (due > 0 && withTimeoutOrNull(due) { wake.receive() } != null) continue
        polledAt = now()
        poll()
    }
}

internal fun spanTrack(publication: TrackPublication, participant: RemoteParticipant) =
    spanTrack(publication.kind, publication.source, publication.sid, participant.identity?.value)

/**
 * Every entry with its standard members as the core takes them, nested maps flattened with a
 * dot (`qualityLimitationDurations.cpu`). No member names are known here.
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
