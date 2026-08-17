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

import io.livekit.android.util.rethrowIfCancellationSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import uniffi.livekit_datatrack.PushFrameErrorReason
import java.io.Closeable
import io.livekit.uniffi.LocalDataTrack as FfiLocalDataTrack

/**
 * A data track published by the local participant.
 *
 * The publication follows this object's lifetime: releasing the last reference unpublishes the
 * track, as does calling [unpublish] or [close]. [Closeable] so `use { }` scopes a publication.
 *
 * ```
 * val track = room.localParticipant.publishDataTrack("telemetry")
 * track.tryPush(DataTrackFrame(payload))
 * track.unpublish()
 * ```
 */
class LocalDataTrack internal constructor(
    private val impl: FfiLocalDataTrack,
) : Closeable {
    /**
     * Whether the track is currently published. Becomes `false` after [unpublish] or if the SFU
     * unpublishes it.
     */
    val isPublished: Boolean
        get() = impl.isPublished()

    /**
     * Metadata for this track.
     */
    val info: DataTrackInfo
        get() = DataTrackInfo(impl.info())

    /**
     * Pushes a frame to subscribers.
     *
     * Non-blocking. Throws [DataTrackPushFrameException.TrackUnpublished] if the track was
     * unpublished by the local participant or the SFU, or if the room is no longer connected;
     * [DataTrackPushFrameException.QueueFull] if frames are being pushed faster than they can
     * be sent, which hands the rejected frame back.
     *
     * @throws DataTrackPushFrameException if the frame could not be enqueued.
     */
    @Throws(DataTrackPushFrameException::class)
    fun tryPush(frame: DataTrackFrame) {
        try {
            impl.tryPush(frame.toFfi())
        } catch (e: PushFrameErrorReason) {
            throw e.toSdk(frame)
        } catch (e: Exception) {
            // The bindings can't decode the reason a push was rejected — the error type is
            // defined in a different UniFFI component — and report an internal error instead.
            // The call did fail, and only two things cause that, so recover the one that
            // applies rather than leaking an FFI-internal error through the public API.
            e.rethrowIfCancellationSignal()
            throw if (isPublished) {
                DataTrackPushFrameException.QueueFull("The send queue is full", frame, e)
            } else {
                DataTrackPushFrameException.TrackUnpublished("The track is no longer published", e)
            }
        }
    }

    /**
     * Unpublishes the track. Subsequent [tryPush] calls throw.
     */
    fun unpublish() {
        impl.unpublish()
    }

    /**
     * Waits until the track is unpublished, by either the local participant or the SFU.
     *
     * Use this to trigger follow-up work once the track is no longer published. Returns
     * immediately if it is already unpublished.
     */
    suspend fun waitForUnpublish() {
        impl.waitForUnpublish()
    }

    /**
     * Unpublishes the track. Same as [unpublish]; provided so `use { }` scopes a publication.
     */
    override fun close() {
        unpublish()
    }

    /**
     * Policy for [send] when the send queue is full.
     */
    enum class FrameDropPolicy {
        /** Propagate [DataTrackPushFrameException.QueueFull] to the caller. */
        THROW,

        /** Silently skip the frame. */
        DROP,
    }

    /**
     * Sends frames from [frames] until the flow completes or the track is unpublished.
     *
     * @param onQueueFull How to handle a full send queue. Defaults to [FrameDropPolicy.DROP].
     * @throws DataTrackPushFrameException if [onQueueFull] is [FrameDropPolicy.THROW] and the
     * queue is full.
     */
    @Throws(DataTrackPushFrameException::class)
    suspend fun send(
        frames: Flow<DataTrackFrame>,
        onQueueFull: FrameDropPolicy = FrameDropPolicy.DROP,
    ) {
        var sending = true
        frames.takeWhile { sending && isPublished }.collect { frame ->
            try {
                tryPush(frame)
            } catch (e: DataTrackPushFrameException) {
                when (e) {
                    is DataTrackPushFrameException.TrackUnpublished -> sending = false
                    is DataTrackPushFrameException.QueueFull ->
                        if (onQueueFull == FrameDropPolicy.THROW) {
                            throw e
                        }
                    is DataTrackPushFrameException.Internal -> throw e
                }
            }
        }
    }
}
