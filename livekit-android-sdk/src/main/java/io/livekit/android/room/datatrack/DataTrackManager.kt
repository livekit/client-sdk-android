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
import io.livekit.uniffi.DataTrackInfo
import io.livekit.uniffi.DataTrackOptions
import io.livekit.uniffi.HandleSignalResponseException
import io.livekit.uniffi.LocalDataTrack
import io.livekit.uniffi.LocalDataTrackManager
import io.livekit.uniffi.LocalDataTrackManagerDelegate
import io.livekit.uniffi.PublishException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Owns the UniFFI [LocalDataTrackManager] and bridges its transport callbacks into [RTCEngine].
 *
 * Signal requests / SFU responses and data-track packets are forwarded through the engine so the
 * Rust manager stays decoupled from WebRTC and WebSocket details.
 *
 * @suppress
 */
@Singleton
class DataTrackManager
@Inject
constructor(
    private val engineProvider: Provider<RTCEngine>,
) {
    private val lock = Any()
    private var localManager: LocalDataTrackManager? = null

    private val delegate = object : LocalDataTrackManagerDelegate {
        override fun onSignalRequest(request: ByteArray) {
            engineProvider.get().sendDataTrackSignalRequest(request)
        }

        override fun onPacketsAvailable(packets: List<ByteArray>) {
            engineProvider.get().sendDataTrackPackets(packets)
        }
    }

    /**
     * Publishes a data track with the given options.
     *
     * @throws PublishException if the SFU rejects the publication or the request fails.
     */
    @Throws(PublishException::class)
    suspend fun publishTrack(options: DataTrackOptions): LocalDataTrack {
        return ensureManager().publishTrack(options)
    }

    /**
     * Forwards a serialized [livekit.LivekitRtc.SignalResponse] containing
     * `PublishDataTrackResponse` to the UniFFI manager.
     */
    fun handleSfuPublishResponse(responseBytes: ByteArray) {
        val manager = localManager ?: return
        try {
            manager.handleSfuPublishResponse(responseBytes)
        } catch (e: HandleSignalResponseException) {
            LKLog.w(e) { "Failed to handle PublishDataTrackResponse" }
        }
    }

    /**
     * Forwards a serialized [livekit.LivekitRtc.SignalResponse] containing `RequestResponse`
     * to the UniFFI manager. Non-data-track request responses are ignored by the manager.
     */
    fun handleSfuRequestResponse(responseBytes: ByteArray) {
        val manager = localManager ?: return
        try {
            manager.handleSfuRequestResponse(responseBytes)
        } catch (e: HandleSignalResponseException) {
            LKLog.w(e) { "Failed to handle RequestResponse for data tracks" }
        }
    }

    /**
     * Republish all tracks after a full reconnect so the SFU recognizes existing publications.
     */
    fun republishTracks() {
        localManager?.republishTracks()
    }

    /**
     * Returns info for all currently published data tracks.
     */
    suspend fun queryTracks(): List<DataTrackInfo> {
        return localManager?.queryTracks() ?: emptyList()
    }

    /**
     * Shuts down the underlying UniFFI manager. A subsequent [publishTrack] creates a new one.
     */
    fun close() {
        synchronized(lock) {
            localManager?.close()
            localManager = null
        }
    }

    private fun ensureManager(): LocalDataTrackManager {
        synchronized(lock) {
            localManager?.let { return it }
            // Encryption provider wiring is left for a follow-up (E2EE).
            return LocalDataTrackManager(delegate, null).also { localManager = it }
        }
    }
}
