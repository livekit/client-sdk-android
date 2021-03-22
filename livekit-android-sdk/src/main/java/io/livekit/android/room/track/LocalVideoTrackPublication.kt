package io.livekit.android.room.track

import livekit.LivekitModels

class LocalVideoTrackPublication(info: LivekitModels.TrackInfo, track: Track? = null) :
    LocalTrackPublication(info, track), VideoTrackPublication {
    override val videoTrack: VideoTrack?
        get() = track as? VideoTrack
}
