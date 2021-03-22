package io.livekit.android.room.track

import livekit.LivekitModels

class RemoteVideoTrackPublication(info: LivekitModels.TrackInfo, track: Track? = null) :
    RemoteTrackPublication(info, track),
    VideoTrackPublication {

    override val videoTrack: VideoTrack?
        get() = track as? VideoTrack
}