package io.livekit.android

import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions

data class RoomOptions(
    /**
     * Automatically manage quality of subscribed video tracks, subscribe to the
     * an appropriate resolution based on the size of the video elements that tracks
     * are attached to.
     *
     * Also observes the visibility of attached tracks and pauses receiving data
     * if they are not visible.
     */
    val autoManageVideo: Boolean = false,

    val audioTrackCaptureDefaults: LocalAudioTrackOptions? = null,
    val videoTrackCaptureDefaults: LocalVideoTrackOptions? = null,
    val audioTrackPublishDefaults: AudioTrackPublishDefaults? = null,
    val videoTrackPublishDefaults: VideoTrackPublishDefaults? = null,
)