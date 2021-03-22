package io.livekit.android.room.track

import livekit.LivekitModels

class RemoteAudioTrackPublication(
    info: LivekitModels.TrackInfo,
    track: Track? = null
) : RemoteTrackPublication(info, track), AudioTrackPublication {
    override val audioTrack: AudioTrack?
        get() = track as? AudioTrack
}