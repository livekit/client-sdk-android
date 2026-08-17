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

import io.livekit.android.events.BroadcastEventBus
import io.livekit.android.room.RTCEngine
import io.livekit.android.util.LKLog
import io.livekit.uniffi.HandleSignalResponseException
import io.livekit.uniffi.RemoteDataTrackManagerDelegate
import io.livekit.uniffi.RemoteDataTrackManagerInterface
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import io.livekit.uniffi.RemoteDataTrack as FfiRemoteDataTrack

/**
 * Owns the UniFFI [io.livekit.uniffi.RemoteDataTrackManager] and bridges its transport callbacks
 * into [RTCEngine].
 *
 * SFU participant / subscriber-handle responses and `_data_track` channel packets are forwarded
 * into the Rust manager; subscription signal requests are sent back out through the engine.
 *
 * Publication events are emitted on [events]. The publisher may not be in the room yet; callers
 * should park the track until [io.livekit.android.room.participant.RemoteParticipant] exists.
 *
 * @suppress
 */
@Singleton
class IncomingDataTrackManager
@Inject
constructor(
    private val engineProvider: Provider<RTCEngine>,
    private val remoteDataTrackManagerFactory: RemoteDataTrackManagerFactory,
) {
    private val eventBus = BroadcastEventBus<IncomingDataTrackEvent>()

    /**
     * Publication and unpublication events from the UniFFI remote manager.
     */
    internal val events = eventBus.readOnly()

    private val lock = Any()
    private var remoteManager: RemoteDataTrackManagerInterface? = null
    private val remoteTracks = mutableListOf<RemoteDataTrack>()

    private val delegate = object : RemoteDataTrackManagerDelegate {
        override fun onSignalRequest(request: ByteArray) {
            engineProvider.get().sendDataTrackSignalRequest(request)
        }

        override fun onTrackPublished(track: FfiRemoteDataTrack) {
            val wrapped = RemoteDataTrack(track)
            synchronized(lock) {
                remoteTracks.add(wrapped)
            }
            eventBus.tryPostEvent(IncomingDataTrackEvent.TrackPublished(wrapped))
        }

        override fun onTrackUnpublished(sid: String) {
            val dataTrackSid = DataTrackSid(sid)
            val unpublished = synchronized(lock) {
                val matches = remoteTracks.filter { it.info.sid == dataTrackSid }
                remoteTracks.removeAll { track -> matches.any { it === track } }
                matches
            }
            for (track in unpublished) {
                eventBus.tryPostEvent(IncomingDataTrackEvent.TrackUnpublished(dataTrackSid, track))
            }
        }
    }

    /**
     * Remote data tracks currently known to the UniFFI manager, including those whose publisher
     * is not yet in the room.
     */
    internal fun snapshotRemoteTracks(): List<RemoteDataTrack> {
        synchronized(lock) {
            return remoteTracks.toList()
        }
    }

    /**
     * Forwards a serialized [livekit.LivekitRtc.SignalResponse] containing a `JoinResponse`
     * to the UniFFI manager so pre-existing remote data tracks are discovered.
     */
    fun handleSfuJoinResponse(responseBytes: ByteArray) {
        try {
            ensureManager().handleSfuJoinResponse(responseBytes)
        } catch (e: HandleSignalResponseException) {
            LKLog.w(e) { "Failed to handle JoinResponse for data tracks" }
        }
    }

    /**
     * Forwards a serialized [livekit.LivekitRtc.SignalResponse] containing a `ParticipantUpdate`
     * to the UniFFI manager.
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
     * Resend subscription updates after reconnect so the SFU re-issues subscriber handles.
     */
    fun resendSubscriptionUpdates() {
        remoteManager?.resendSubscriptionUpdates()
    }

    /**
     * Shuts down the underlying UniFFI manager. A subsequent handle call creates a new one.
     */
    fun close() {
        synchronized(lock) {
            (remoteManager as? AutoCloseable)?.close()
            remoteManager = null
            remoteTracks.clear()
        }
    }

    private fun ensureManager(): RemoteDataTrackManagerInterface {
        synchronized(lock) {
            remoteManager?.let { return it }
            return remoteDataTrackManagerFactory.create(delegate).also { remoteManager = it }
        }
    }
}
