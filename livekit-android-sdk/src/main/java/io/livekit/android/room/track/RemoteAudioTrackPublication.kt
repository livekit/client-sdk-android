package io.livekit.android.room.track

import livekit.Model

class RemoteAudioTrackPublication(
    info: Model.TrackInfo,
    track: Track? = null
) : RemoteTrackPublication(info, track), AudioTrackPublication {
    override val audioTrack: AudioTrack?
        get() = track as? AudioTrack
}