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

import androidx.annotation.CheckResult
import io.livekit.android.e2ee.DataTrackCryptor
import io.livekit.android.room.RTCEngine
import io.livekit.android.util.LKLog
import io.livekit.android.util.rethrowIfCancellationSignal
import io.livekit.uniffi.DataTrackOptions
import io.livekit.uniffi.HandleSignalResponseException
import io.livekit.uniffi.LocalDataTrackManagerDelegate
import io.livekit.uniffi.LocalDataTrackManagerInterface
import uniffi.livekit_datatrack.PublishException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Owns the UniFFI [io.livekit.uniffi.LocalDataTrackManager] and bridges its transport callbacks
 * into [RTCEngine].
 *
 * Signal requests / SFU responses and data-track packets are forwarded through the engine so the
 * Rust manager stays decoupled from WebRTC and WebSocket details.
 *
 * @suppress
 */
@Singleton
class OutgoingDataTrackManager
@Inject
constructor(
    private val engineProvider: Provider<RTCEngine>,
    private val localDataTrackManagerFactory: LocalDataTrackManagerFactory,
) {
    private val lock = Any()
    private var localManager: LocalDataTrackManagerInterface? = null
    private var nativeUnavailable = false
    private val cryptor = DataTrackCryptor { engineProvider.get().e2EEManager }

    /**
     * Handles events from the UniFFI local data track manager.
     */
    private val delegate = object : LocalDataTrackManagerDelegate {
        override fun onSignalRequest(request: ByteArray) {
            engineProvider.get().sendDataTrackSignalRequest(request)
        }

        override fun onPacketsAvailable(packets: List<ByteArray>) {
            engineProvider.get().sendDataTrackPackets(packets)
        }
    }

    /**
     * Publishes a data track with the given name and options.
     *
     * @return A successful [Result] containing the published track, or a failure containing
     * [DataTrackPublishException].
     */
    @CheckResult
    suspend fun publishTrack(name: String, options: DataTrackPublishOptions? = null): Result<LocalDataTrack> {
        val ffiOptions = DataTrackOptions(
            name = name,
            schema = options?.frameFormat?.schema?.toFfi(),
            frameEncoding = options?.frameFormat?.frameEncoding?.toFfi(),
        )
        try {
            engineProvider.get().ensureDataTrackPublisherConnected()
        } catch (e: DataTrackPublishException) {
            return Result.failure(e)
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            return Result.failure(
                DataTrackPublishException.Disconnected(
                    e.message ?: "Lost the connection while establishing the publisher data track channel",
                    e,
                ),
            )
        }
        val manager = ensureManager()
            ?: return Result.failure(
                DataTrackPublishException.Internal(
                    "Data tracks are unavailable: the native library failed to load",
                ),
            )
        return try {
            Result.success(LocalDataTrack(manager.publishTrack(ffiOptions)))
        } catch (e: PublishException) {
            Result.failure(e.toSdk())
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            Result.failure(DataTrackPublishException.Internal(e.message ?: "", e))
        }
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
     * Receives a serialized [livekit.LivekitRtc.SignalResponse] containing
     * `UnpublishDataTrackResponse`.
     *
     * UniFFI does not consume this message yet. Local unpublish is applied by
     * [LocalDataTrack.unpublish] before the SFU acks.
     */
    fun handleSfuUnpublishResponse(responseBytes: ByteArray) {
        // UniFFI does not consume UnpublishDataTrackResponse.
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
     * Returns serialized `PublishDataTrackResponse` messages for currently published tracks,
     * suitable for [livekit.LivekitRtc.SyncState.publishDataTracks].
     */
    suspend fun publishResponsesForSyncState(): List<ByteArray> {
        return localManager?.publishResponsesForSyncState() ?: emptyList()
    }

    /**
     * Shuts down the underlying UniFFI manager. A subsequent [publishTrack] creates a new one.
     */
    fun close() {
        synchronized(lock) {
            (localManager as? AutoCloseable)?.close()
            localManager = null
        }
    }

    /**
     * The UniFFI manager, or `null` if its native library could not be loaded — see
     * [IncomingDataTrackManager]. Reached only from [publishTrack], so the failure surfaces to
     * the caller as a failed [Result] rather than degrading silently.
     */
    private fun ensureManager(): LocalDataTrackManagerInterface? {
        synchronized(lock) {
            localManager?.let { return it }
            if (nativeUnavailable) {
                return null
            }
            // Whether frames are encrypted is fixed when the manager is built: unlike data
            // channel payloads (a per-message property), data track encryption is a track-level
            // protocol property that subscribers key their decryption on. The cryptor is passed
            // only when E2EE is on — its presence is what marks published tracks as encrypted
            // ([DataTrackInfo.usesE2ee]).
            val encryptionProvider = cryptor.takeIf {
                engineProvider.get().e2EEManager?.isDataTrackEncryptionEnabled() == true
            }
            return try {
                localDataTrackManagerFactory.create(delegate, encryptionProvider)
                    .also { localManager = it }
            } catch (e: LinkageError) {
                nativeUnavailable = true
                LKLog.e(e) { "Data tracks are unavailable: the native library failed to load." }
                null
            }
        }
    }
}
