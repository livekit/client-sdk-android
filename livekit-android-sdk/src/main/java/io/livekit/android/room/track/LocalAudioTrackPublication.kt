package io.livekit.android.room.track

import livekit.LivekitModels

class LocalAudioTrackPublication(info: LivekitModels.TrackInfo, track: Track? = null) :
    LocalTrackPublication(info, track), AudioTrackPublication {
    override val audioTrack: AudioTrack?
        get() = track as? AudioTrack
}
