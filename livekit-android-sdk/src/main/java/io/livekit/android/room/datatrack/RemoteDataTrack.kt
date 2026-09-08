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
import androidx.annotation.IntRange
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.rethrowIfCancellationSignal
import io.livekit.uniffi.DataTrackSubscribeOptions
import io.livekit.uniffi.RemoteDataTrack as FfiRemoteDataTrack
import uniffi.livekit_datatrack.DataTrackSubscribeException as FfiSubscribeException

/**
 * A data track published by a remote participant.
 *
 * Call [subscribe] to start receiving frames.
 *
 * ```
 * remoteTrack.subscribe().onSuccess { stream ->
 *     stream.flow.collect { frame -> process(frame.payload) }
 * }
 * ```
 */
class RemoteDataTrack internal constructor(
    private val impl: FfiRemoteDataTrack,
) {
    /**
     * Identity of the participant publishing this track.
     */
    val publisherIdentity: Participant.Identity = Participant.Identity(impl.publisherIdentity())

    /**
     * Name chosen by the publisher; unique per participant.
     *
     * This is a stable identifier across reconnects, unlike [DataTrackInfo.sid].
     */
    val name: String = impl.info().name

    /**
     * Whether the track is currently published by the remote participant.
     */
    val isPublished: Boolean
        get() = impl.isPublished()

    /**
     * Metadata for this track.
     */
    val info: DataTrackInfo
        get() = DataTrackInfo(impl.info())

    /**
     * Waits until the track is unpublished, by either the publisher or the SFU.
     *
     * Use this to trigger follow-up work once the track is no longer published. Returns
     * immediately if it is already unpublished.
     */
    suspend fun waitForUnpublish() {
        impl.waitForUnpublish()
    }

    /**
     * Subscribes to the track and returns a [DataTrackStream] of incoming frames.
     *
     * Subscribing more than once is allowed: the streams share one pipeline, each receives every
     * frame from the moment it subscribes (nothing is replayed), and later calls don't change
     * the buffer size.
     *
     * @param bufferSize Maximum number of received frames buffered internally before the oldest
     * is dropped. Values below 1 are clamped to 1.
     * @return A successful [Result] containing the [DataTrackStream], or a failure containing
     * [DataTrackSubscribeException].
     */
    @CheckResult
    suspend fun subscribe(
        @IntRange(from = 1) bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ): Result<DataTrackStream> {
        val options = DataTrackSubscribeOptions(bufferSize = bufferSize.coerceAtLeast(1).toUInt())
        return try {
            Result.success(DataTrackStream(impl.subscribeWithOptions(options)))
        } catch (e: FfiSubscribeException) {
            Result.failure(e.toSdk())
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            Result.failure(DataTrackSubscribeException.Internal(e.message ?: "", e))
        }
    }

    companion object {
        /**
         * Default subscribe-side buffer, in frames.
         */
        const val DEFAULT_BUFFER_SIZE: Int = 16
    }
}
