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

package io.livekit.android.room.datatrack

import io.livekit.android.util.flow
import io.livekit.android.webrtc.DataChannelManager
import io.livekit.android.webrtc.peerconnection.RTCThreadToken
import io.livekit.android.webrtc.peerconnection.executeOnRTCThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.DataChannel

/**
 * Owns the publisher `_data_track` transport: the frame sender that drains into it, the pump that
 * follows its buffered-amount and state changes, and the readiness wait a publish blocks on.
 *
 * The transport is swapped, not recreated, across a full reconnect — [attach] hands over a
 * replacement [DataChannelManager] for the same session while the frame sender lives on, and
 * [awaitOpen] re-reads the current manager each pass so an in-flight publish waits for that
 * replacement instead of failing against a disposed one.
 *
 * @suppress
 */
internal class DataTrackPublisherChannel(
    private val rtcThreadToken: RTCThreadToken,
) {
    private val frameSender = DataTrackFrameSender()
    private var channelManager: DataChannelManager? = null
    private var pumpJob: Job? = null

    /**
     * Adopts [channelManager] as the transport: same frame sender, new SCTP association. A full
     * reconnect's replacement arrives unopened; [awaitOpen] callers keep waiting until it hits
     * [DataChannel.State.OPEN].
     */
    fun attach(channelManager: DataChannelManager, scope: CoroutineScope) {
        this.channelManager = channelManager
        // Frames queued for the old channel belong to the torn-down transport.
        frameSender.attach(DataChannelManagerSendChannel(channelManager))
        pumpJob?.cancel()
        pumpJob = scope.launch {
            launch {
                channelManager::bufferedAmount.flow.collect {
                    pump()
                }
            }
            launch {
                channelManager::state.flow.collect {
                    pump()
                }
            }
        }
    }

    /**
     * Tears down the current transport. A later [attach] revives the sender against the
     * replacement channel.
     */
    fun detach() {
        pumpJob?.cancel()
        pumpJob = null
        frameSender.attach(null)
        channelManager?.dispose()
        channelManager = null
    }

    /**
     * Queues serialized data-track packets.
     *
     * Packets belonging to one application frame are metered as a unit (drop-oldest, one frame in
     * flight) once the channel is [DataChannel.State.OPEN] and buffered amount is at or below
     * [DataTrackFrameSender.LOW_WATER_MARK].
     */
    fun sendPackets(packets: List<ByteArray>) {
        executeOnRTCThread(rtcThreadToken) {
            frameSender.sendOrQueue(packets)
        }
    }

    /**
     * Waits until the channel is open.
     *
     * @param sessionClosed whether the room session itself has ended, which ends the wait instead
     * of letting it run out the timeout.
     * @return `true` once open, `false` if the session closed first, `null` on timeout.
     */
    suspend fun awaitOpen(timeoutMs: Long, sessionClosed: () -> Boolean): Boolean? =
        withTimeoutOrNull(timeoutMs) {
            while (!sessionClosed()) {
                val manager = channelManager
                if (manager?.disposed == false && manager.state == DataChannel.State.OPEN) {
                    return@withTimeoutOrNull true
                }
                delay(POLL_INTERVAL_MS)
            }
            false
        }

    private fun pump() {
        executeOnRTCThread(rtcThreadToken) {
            frameSender.pump()
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 50L
    }
}
