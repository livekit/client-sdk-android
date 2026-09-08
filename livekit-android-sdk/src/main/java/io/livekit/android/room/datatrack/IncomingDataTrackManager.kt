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

import io.livekit.android.e2ee.DataTrackCryptor
import io.livekit.android.events.BroadcastEventBus
import io.livekit.android.room.RTCEngine
import io.livekit.android.util.LKLog
import io.livekit.android.util.rethrowIfCancellationSignal
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
    private var nativeUnavailable = false
    private val remoteTracks = mutableListOf<RemoteDataTrack>()
    private val cryptor = DataTrackCryptor { engineProvider.get().e2EEManager }

    /**
     * Handles events from the UniFFI remote data track manager.
     */
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
     * Returns a snapshot of the remote data tracks currently known to the
     * UniFFI manager, including those whose publisher is not yet in the room.
     */
    internal fun snapshotRemoteTracks(): List<RemoteDataTrack> {
        synchronized(lock) {
            return remoteTracks.toList()
        }
    }

    /**
     * Forwards a serialized [livekit.LivekitRtc.SignalResponse] containing a `JoinResponse`
     * to the UniFFI manager so pre-existing remote data tracks are discovered. Pass the
     * websocket bytes as received; re-encoding a decoded copy can drop newer fields.
     */
    fun handleSfuJoinResponse(responseBytes: ByteArray) {
        val manager = ensureManager() ?: return
        try {
            manager.handleSfuJoinResponse(responseBytes)
        } catch (e: HandleSignalResponseException) {
            LKLog.w(e) { "Failed to handle JoinResponse for data tracks" }
        }
    }

    /**
     * Forwards a serialized [livekit.LivekitRtc.SignalResponse] containing a `ParticipantUpdate`
     * to the UniFFI manager. Pass the websocket bytes as received.
     */
    fun handleSfuParticipantUpdate(responseBytes: ByteArray, localParticipantIdentity: String) {
        val manager = ensureManager() ?: return
        try {
            manager.handleSfuParticipantUpdate(responseBytes, localParticipantIdentity)
        } catch (e: HandleSignalResponseException) {
            LKLog.w(e) { "Failed to handle participant update for data tracks" }
        }
    }

    /**
     * Forwards a serialized [livekit.LivekitRtc.SignalResponse] containing
     * `DataTrackSubscriberHandles` to the UniFFI manager. Pass the websocket bytes as received.
     */
    fun handleSubscriberHandles(responseBytes: ByteArray) {
        val manager = ensureManager() ?: return
        try {
            manager.handleSubscriberHandles(responseBytes)
        } catch (e: HandleSignalResponseException) {
            LKLog.w(e) { "Failed to handle DataTrackSubscriberHandles" }
        }
    }

    /**
     * Forwards a packet received on the `_data_track` data channel to the UniFFI manager.
     *
     * Called on a WebRTC callback thread, so nothing may escape: a throw here takes down the
     * process rather than surfacing anywhere the app can handle it.
     */
    fun handlePacketReceived(packet: ByteArray) {
        val manager = ensureManager() ?: return
        try {
            manager.handlePacketReceived(packet)
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            LKLog.w(e) { "Failed to handle a data track packet" }
        }
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

    /**
     * The UniFFI manager, or `null` if its native library could not be loaded.
     *
     * Loading can fail on a device the packaged APK has no ABI for, among other reasons. Data
     * tracks are then unavailable — but this runs on every connect and on the WebRTC receive
     * path, so a failure must not fail [io.livekit.android.room.Room.connect] or crash the
     * process for apps that never publish or subscribe to one. The failure is latched so the
     * load is not retried per call, and every entry point above degrades to a no-op.
     */
    private fun ensureManager(): RemoteDataTrackManagerInterface? {
        synchronized(lock) {
            remoteManager?.let { return it }
            if (nativeUnavailable) {
                return null
            }
            return try {
                remoteDataTrackManagerFactory.create(delegate, cryptor).also { remoteManager = it }
            } catch (e: LinkageError) {
                nativeUnavailable = true
                LKLog.e(e) { "Data tracks are unavailable: the native library failed to load." }
                null
            }
        }
    }
}
