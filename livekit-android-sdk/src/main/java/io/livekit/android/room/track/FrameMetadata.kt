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

package io.livekit.android.room.track

import io.livekit.android.annotations.Beta
import livekit.LivekitModels.PacketTrailerFeature
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.FrameMetadata as RtcFrameMetadata

/**
 * Metadata sent along with a single video frame.
 *
 * A field set to 0, or a null or empty [userData], isn't sent.
 *
 * @property userTimestampUs A timestamp in microseconds. By default, the publisher's capture time
 * as wall-clock time. See [FrameMetadataPublishOptions.timestamp].
 * @property frameId A frame ID from 0 to 4294967295. By default, a per-track counter.
 * See [FrameMetadataPublishOptions.frameId].
 * @property userData Bytes supplied by the publishing app. See [FrameMetadataPublishOptions.userData].
 */
@Beta
data class FrameMetadata(
    val userTimestampUs: Long = 0,
    val frameId: Long = 0,
    val userData: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameMetadata) return false
        return userTimestampUs == other.userTimestampUs &&
            frameId == other.frameId &&
            userData.contentEquals(other.userData)
    }

    override fun hashCode(): Int {
        var result = userTimestampUs.hashCode()
        result = 31 * result + frameId.hashCode()
        result = 31 * result + (userData?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Declares which frame metadata to send with a published video track. Subscribers read it with
 * [RemoteVideoTrack.lookupFrameMetadata].
 *
 * Each option turns on the associated [FrameMetadata] field, which the SDK fills in by default.
 * To send your own values, use [LocalVideoTrack.frameMetadataProvider]. They're sent even for a field whose option is off, as
 * long as at least one option is on.
 *
 * @property timestamp Send a timestamp with each frame. By default it's the frame's capture time,
 * in wall-clock microseconds. If your video processor changes frame timestamps, supply your own.
 * @property frameId Send a frame ID with each frame. By default it's a per-track counter starting
 * at 1. IDs skip frames that are dropped, and can repeat when a subscriber switches simulcast
 * layers.
 * @property userData Send user data, which you supply. **It isn't encrypted, even with end-to-end
 * encryption.** Each frame can carry up to 232 bytes, or 248 with the other options off. User data
 * that doesn't fit, or that would form an H.264 or H.265 start code, is dropped from that frame.
 */
data class FrameMetadataPublishOptions(
    val timestamp: Boolean = false,
    val frameId: Boolean = false,
    val userData: Boolean = false,
)

/**
 * Supplies frame metadata for a local video track, through [LocalVideoTrack.frameMetadataProvider].
 *
 * It's called for every frame on the video capture path, so should be kept fast and non-blocking.
 */
@Beta
fun interface FrameMetadataProvider {
    /**
     * @param frame The frame about to be encoded.
     * @param current The values so far: any metadata a video processor set on the frame, with the
     * defaults turned on in [FrameMetadataPublishOptions] filled into fields that are 0.
     * @return New values to send instead of [current], or null to send [current]. A field set to
     * 0 isn't sent. If this throws, [current] is sent.
     */
    fun provide(frame: VideoFrame, current: FrameMetadata): FrameMetadata?
}

/**
 * A frame metadata field that a publisher declared for a video track, so that subscribers know to
 * expect it. See [TrackPublication.frameMetadataFeatures].
 */
@Beta
enum class FrameMetadataFeature {
    /** Frames carry a timestamp. See [FrameMetadata.userTimestampUs]. */
    TIMESTAMP,

    /** Frames carry a frame ID. See [FrameMetadata.frameId]. */
    FRAME_ID,

    /** Frames carry user data. See [FrameMetadata.userData]. */
    USER_DATA,
    ;

    internal fun toProto(): PacketTrailerFeature = when (this) {
        TIMESTAMP -> PacketTrailerFeature.PTF_USER_TIMESTAMP
        FRAME_ID -> PacketTrailerFeature.PTF_FRAME_ID
        USER_DATA -> PacketTrailerFeature.PTF_USER_DATA
    }

    internal companion object {
        /**
         * Returns null for a value this SDK build doesn't know, so a publisher declaring a newer
         * feature isn't an error.
         */
        fun fromProto(feature: PacketTrailerFeature): FrameMetadataFeature? = when (feature) {
            PacketTrailerFeature.PTF_USER_TIMESTAMP -> TIMESTAMP
            PacketTrailerFeature.PTF_FRAME_ID -> FRAME_ID
            PacketTrailerFeature.PTF_USER_DATA -> USER_DATA
            PacketTrailerFeature.UNRECOGNIZED -> null
        }
    }
}

internal fun FrameMetadataPublishOptions?.hasFeatures(): Boolean =
    this != null && (timestamp || frameId || userData)

internal fun FrameMetadataPublishOptions.toPacketTrailerFeatures(): List<PacketTrailerFeature> = buildList {
    if (timestamp) add(FrameMetadataFeature.TIMESTAMP)
    if (frameId) add(FrameMetadataFeature.FRAME_ID)
    if (userData) add(FrameMetadataFeature.USER_DATA)
}.map { it.toProto() }

internal fun FrameMetadata.toRtc(): RtcFrameMetadata = RtcFrameMetadata(userTimestampUs, frameId, userData)

internal fun RtcFrameMetadata.toSdk(): FrameMetadata = FrameMetadata(userTimestampUs, frameId, userData)
