package io.livekit.android.room.track

import livekit.Model

class LocalVideoTrackPublication(info: Model.TrackInfo, track: Track? = null) :
    LocalTrackPublication(info, track), VideoTrackPublication {
    override val videoTrack: VideoTrack?
        get() = track as? VideoTrack
}
