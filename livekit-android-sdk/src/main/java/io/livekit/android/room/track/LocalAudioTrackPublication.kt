package io.livekit.android.room.track

import livekit.Model

class LocalAudioTrackPublication(info: Model.TrackInfo, track: Track? = null) :
    LocalTrackPublication(info, track), AudioTrackPublication {
    override val audioTrack: AudioTrack?
        get() = track as? AudioTrack
}
