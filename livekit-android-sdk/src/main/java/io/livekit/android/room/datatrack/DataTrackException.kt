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

import uniffi.livekit_datatrack.DataTrackSubscribeException as FfiSubscribeException
import uniffi.livekit_datatrack.PublishException as FfiPublishException
import uniffi.livekit_datatrack.PushFrameErrorReason as FfiPushFrameErrorReason

/**
 * An error raised while publishing a [LocalDataTrack].
 */
sealed class DataTrackPublishException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The participant is not permitted to publish data tracks.
     */
    class NotAllowed(message: String, cause: Throwable? = null) : DataTrackPublishException(message, cause)

    /**
     * A data track with the same name is already published by this participant.
     */
    class DuplicateName(message: String, cause: Throwable? = null) : DataTrackPublishException(message, cause)

    /**
     * The requested track name is invalid.
     */
    class InvalidName(message: String, cause: Throwable? = null) : DataTrackPublishException(message, cause)

    /**
     * The SFU did not respond to the publish request in time.
     */
    class Timeout(message: String, cause: Throwable? = null) : DataTrackPublishException(message, cause)

    /**
     * The maximum number of data tracks for this participant has been reached.
     */
    class LimitReached(message: String, cause: Throwable? = null) : DataTrackPublishException(message, cause)

    /**
     * The connection was lost before the publish completed.
     */
    class Disconnected(message: String, cause: Throwable? = null) : DataTrackPublishException(message, cause)

    /**
     * The track's schema metadata is invalid.
     */
    class InvalidSchema(message: String, cause: Throwable? = null) : DataTrackPublishException(message, cause)

    /**
     * An unexpected internal error occurred.
     */
    class Internal(message: String, cause: Throwable? = null) : DataTrackPublishException(message, cause)
}

/**
 * The reason a frame could not be pushed via [LocalDataTrack.tryPush].
 */
sealed class DataTrackPushFrameException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The track has been unpublished, by either the local participant or the SFU.
     */
    class TrackUnpublished(message: String, cause: Throwable? = null) : DataTrackPushFrameException(message, cause)

    /**
     * The send queue is full; the frame was not enqueued.
     *
     * The rejected [frame] — the same instance that was pushed, not a copy — comes back so it can
     * be retried or re-queued. Mainly for [LocalDataTrack.send], where frames come from a [kotlinx.coroutines.flow.Flow]
     * and the caller holds no reference of its own.
     */
    class QueueFull(
        message: String,
        val frame: DataTrackFrame,
        cause: Throwable? = null,
    ) : DataTrackPushFrameException(message, cause)

    /**
     * An unexpected internal error occurred.
     */
    class Internal(message: String, cause: Throwable? = null) : DataTrackPushFrameException(message, cause)
}

/**
 * An error raised while subscribing to a [RemoteDataTrack].
 */
sealed class DataTrackSubscribeException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The track was unpublished before the subscription completed.
     */
    class Unpublished(message: String, cause: Throwable? = null) : DataTrackSubscribeException(message, cause)

    /**
     * The SFU did not respond to the subscribe request in time.
     */
    class Timeout(message: String, cause: Throwable? = null) : DataTrackSubscribeException(message, cause)

    /**
     * The connection was lost before the subscription completed.
     */
    class Disconnected(message: String, cause: Throwable? = null) : DataTrackSubscribeException(message, cause)

    /**
     * An unexpected internal error occurred.
     */
    class Internal(message: String, cause: Throwable? = null) : DataTrackSubscribeException(message, cause)
}

@Suppress("CyclomaticComplexMethod") // Mechanical 1:1 mapping of UniFFI publish error cases.
internal fun FfiPublishException.toSdk(): DataTrackPublishException = when (this) {
    is FfiPublishException.NotAllowed -> DataTrackPublishException.NotAllowed(message ?: "", this)
    is FfiPublishException.DuplicateName -> DataTrackPublishException.DuplicateName(message ?: "", this)
    is FfiPublishException.InvalidName -> DataTrackPublishException.InvalidName(message ?: "", this)
    is FfiPublishException.Timeout -> DataTrackPublishException.Timeout(message ?: "", this)
    is FfiPublishException.LimitReached -> DataTrackPublishException.LimitReached(message ?: "", this)
    is FfiPublishException.Disconnected -> DataTrackPublishException.Disconnected(message ?: "", this)
    is FfiPublishException.InvalidSchema -> DataTrackPublishException.InvalidSchema(message ?: "", this)
    is FfiPublishException.Internal -> DataTrackPublishException.Internal(message ?: "", this)
}

internal fun FfiPushFrameErrorReason.toSdk(frame: DataTrackFrame): DataTrackPushFrameException = when (this) {
    is FfiPushFrameErrorReason.TrackUnpublished -> DataTrackPushFrameException.TrackUnpublished(message ?: "", this)
    is FfiPushFrameErrorReason.QueueFull -> DataTrackPushFrameException.QueueFull(message ?: "", frame, this)
}

internal fun FfiSubscribeException.toSdk(): DataTrackSubscribeException = when (this) {
    is FfiSubscribeException.Unpublished -> DataTrackSubscribeException.Unpublished(message ?: "", this)
    is FfiSubscribeException.Timeout -> DataTrackSubscribeException.Timeout(message ?: "", this)
    is FfiSubscribeException.Disconnected -> DataTrackSubscribeException.Disconnected(message ?: "", this)
    is FfiSubscribeException.Internal -> DataTrackSubscribeException.Internal(message ?: "", this)
}
