package io.livekit.android.room.track

import livekit.Model

class RemoteVideoTrackPublication(info: Model.TrackInfo, track: Track? = null) :
    RemoteTrackPublication(info, track),
    VideoTrackPublication {

    override val videoTrack: VideoTrack?
        get() = track as? VideoTrack
}