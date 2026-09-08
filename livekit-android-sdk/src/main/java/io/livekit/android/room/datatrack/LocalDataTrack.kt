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
import io.livekit.android.util.rethrowIfCancellationSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import uniffi.livekit_datatrack.PushFrameErrorReason
import io.livekit.uniffi.LocalDataTrack as FfiLocalDataTrack

/**
 * A data track published by the local participant. Obtain one from
 * [io.livekit.android.room.participant.LocalParticipant.publishDataTrack],
 * then push frames with [tryPush] or [send].
 *
 * The publication stays live until [unpublish] is called, the SFU unpublishes
 * the track, or the room disconnects. Dropping the last reference eventually
 * unpublishes the track, but only once it is garbage collected — call
 * [unpublish] to end the publication at a predictable point, or
 * [io.livekit.android.room.participant.LocalParticipant.withDataTrack] to scope
 * one to a block.
 *
 * ```
 * val result = room.localParticipant.publishDataTrack("telemetry")
 * result.onSuccess { track ->
 *     track.tryPush(DataTrackFrame(payload))
 *     track.unpublish()
 * }
 * ```
 */
class LocalDataTrack internal constructor(
    private val impl: FfiLocalDataTrack,
) : DataTrackFrameSink {
    /**
     * Whether the track is currently published. Becomes `false` after [unpublish] or if the SFU
     * unpublishes it.
     */
    override val isPublished: Boolean
        get() = impl.isPublished()

    /**
     * Metadata for this track.
     */
    val info: DataTrackInfo
        get() = DataTrackInfo(impl.info())

    /**
     * Pushes a frame to subscribers.
     *
     * Non-blocking. Fails with [DataTrackPushFrameException.TrackUnpublished] if the track was
     * unpublished by the local participant or the SFU, or if the room is no longer connected;
     * [DataTrackPushFrameException.QueueFull] if frames are being pushed faster than they can
     * be sent, which hands the rejected frame back on the exception.
     *
     * @return A successful [Result] if the frame was enqueued, or a failure containing
     * [DataTrackPushFrameException].
     */
    @CheckResult
    override fun tryPush(frame: DataTrackFrame): Result<Unit> {
        return try {
            impl.tryPush(frame.toFfi())
            Result.success(Unit)
        } catch (e: PushFrameErrorReason) {
            Result.failure(e.toSdk(frame))
        } catch (e: Exception) {
            // The bindings can't decode the reason a push was rejected — the error type is
            // defined in a different UniFFI component — and report an internal error instead.
            // The call did fail, and only two things cause that, so recover the one that
            // applies rather than leaking an FFI-internal error through the public API.
            e.rethrowIfCancellationSignal()
            Result.failure(
                if (isPublished) {
                    DataTrackPushFrameException.QueueFull("The send queue is full", frame, e)
                } else {
                    DataTrackPushFrameException.TrackUnpublished("The track is no longer published", e)
                },
            )
        }
    }

    /**
     * Unpublishes the track. Subsequent [tryPush] calls fail with
     * [DataTrackPushFrameException.TrackUnpublished].
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
     * Policy for [send] when the send queue is full.
     */
    enum class FrameDropPolicy {
        /** Fail the send with [DataTrackPushFrameException.QueueFull]. */
        FAIL,

        /** Silently skip the frame. */
        DROP,
    }

    /**
     * Sends frames from [frames] until it ends or the track is unpublished.
     *
     * @param onQueueFull How to handle a full send queue. Defaults to [FrameDropPolicy.DROP].
     * @return A successful [Result] if every frame was sent or dropped per [onQueueFull], or if
     * the track is unpublished mid-send. A failure containing [DataTrackPushFrameException] if
     * [onQueueFull] is [FrameDropPolicy.FAIL] and the queue is full.
     */
    @CheckResult
    suspend fun send(
        frames: Flow<DataTrackFrame>,
        onQueueFull: FrameDropPolicy = FrameDropPolicy.DROP,
    ): Result<Unit> = sendFrames(frames, onQueueFull)
}

/**
 * The slice of a publication the sequence send drives — a seam so the queue-full policy is
 * unit-testable, since saturating a live pipeline to observe it is inherently timing-dependent.
 *
 * @suppress
 */
internal interface DataTrackFrameSink {
    val isPublished: Boolean
    fun tryPush(frame: DataTrackFrame): Result<Unit>
}

internal suspend fun DataTrackFrameSink.sendFrames(
    source: Flow<DataTrackFrame>,
    onQueueFull: LocalDataTrack.FrameDropPolicy,
): Result<Unit> {
    var outcome: Result<Unit>? = null
    source.takeWhile { isPublished && outcome == null }.collect { frame ->
        outcome = sendOne(frame, onQueueFull)
    }
    return outcome ?: Result.success(Unit)
}

/**
 * @return `null` to keep sending, or a [Result] that ends the send — success if the track was
 * unpublished, failure otherwise.
 */
private fun DataTrackFrameSink.sendOne(
    frame: DataTrackFrame,
    onQueueFull: LocalDataTrack.FrameDropPolicy,
): Result<Unit>? {
    if (!isPublished) return Result.success(Unit)
    val error = tryPush(frame).exceptionOrNull() ?: return null
    // The track can be unpublished between the check above and the push; end the send as
    // documented rather than surfacing an error.
    return when (error) {
        is DataTrackPushFrameException.TrackUnpublished -> Result.success(Unit)
        is DataTrackPushFrameException.QueueFull ->
            if (onQueueFull == LocalDataTrack.FrameDropPolicy.FAIL) {
                Result.failure(error)
            } else {
                null
            }
        is DataTrackPushFrameException -> Result.failure(error)
        else -> Result.failure(DataTrackPushFrameException.Internal(error.message ?: "", error))
    }
}
