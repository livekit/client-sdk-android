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

import io.livekit.android.room.RTCEngine
import io.livekit.android.util.LKLog
import io.livekit.uniffi.DataTrackSid
import io.livekit.uniffi.HandleSignalResponseException
import io.livekit.uniffi.RemoteDataTrack
import io.livekit.uniffi.RemoteDataTrackManager
import io.livekit.uniffi.RemoteDataTrackManagerDelegate
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Owns the UniFFI [RemoteDataTrackManager] and bridges its transport callbacks into [RTCEngine].
 *
 * SFU participant / subscriber-handle responses and `_data_track` channel packets are forwarded
 * into the Rust manager; subscription signal requests are sent back out through the engine.
 *
 * @suppress
 */
@Singleton
class IncomingDataTrackManager
@Inject
constructor(
    private val engineProvider: Provider<RTCEngine>,
) {
    /**
     * Optional listener for remote data-track publication events.
     */
    interface Listener {
        fun onTrackPublished(track: RemoteDataTrack)
        fun onTrackUnpublished(sid: DataTrackSid)
    }

    var listener: Listener? = null

    private val lock = Any()
    private var remoteManager: RemoteDataTrackManager? = null

    private val delegate = object : RemoteDataTrackManagerDelegate {
        override fun onSignalRequest(request: ByteArray) {
            engineProvider.get().sendDataTrackSignalRequest(request)
        }

        override fun onTrackPublished(track: RemoteDataTrack) {
            listener?.onTrackPublished(track)
                ?: LKLog.d { "Remote data track published: ${track.info().sid}" }
        }

        override fun onTrackUnpublished(sid: DataTrackSid) {
            listener?.onTrackUnpublished(sid)
                ?: LKLog.d { "Remote data track unpublished: $sid" }
        }
    }

    /**
     * Forwards a serialized [livekit.LivekitRtc.SignalResponse] containing a `ParticipantUpdate`
     * (or an equivalent update built from join `otherParticipants`) to the UniFFI manager.
     */
    fun handleSfuParticipantUpdate(responseBytes: ByteArray, localParticipantIdentity: String) {
        try {
            ensureManager().handleSfuParticipantUpdate(responseBytes, localParticipantIdentity)
        } catch (e: HandleSignalResponseException) {
            LKLog.w(e) { "Failed to handle participant update for data tracks" }
        }
    }

    /**
     * Forwards a serialized [livekit.LivekitRtc.SignalResponse] containing
     * `DataTrackSubscriberHandles` to the UniFFI manager.
     */
    fun handleSubscriberHandles(responseBytes: ByteArray) {
        try {
            ensureManager().handleSubscriberHandles(responseBytes)
        } catch (e: HandleSignalResponseException) {
            LKLog.w(e) { "Failed to handle DataTrackSubscriberHandles" }
        }
    }

    /**
     * Forwards a packet received on the `_data_track` data channel to the UniFFI manager.
     */
    fun handlePacketReceived(packet: ByteArray) {
        ensureManager().handlePacketReceived(packet)
    }

    /**
     * Resend subscription updates after a full reconnect so the SFU knows which tracks are subscribed.
     */
    fun resendSubscriptionUpdates() {
        remoteManager?.resendSubscriptionUpdates()
    }

    /**
     * Shuts down the underlying UniFFI manager. A subsequent handle call creates a new one.
     */
    fun close() {
        synchronized(lock) {
            remoteManager?.close()
            remoteManager = null
        }
    }

    private fun ensureManager(): RemoteDataTrackManager {
        synchronized(lock) {
            remoteManager?.let { return it }
            // Decryption provider wiring is left for a follow-up (E2EE).
            return RemoteDataTrackManager(delegate, null).also { remoteManager = it }
        }
    }
}
