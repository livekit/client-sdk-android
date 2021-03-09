package io.livekit.android.room.track

import livekit.Model

open class RemoteTrackPublication(info: Model.TrackInfo, track: Track? = null) :
    TrackPublication(info, track) {

    val remoteTrack: Track?
        get() = track

    val trackSubscribed: Boolean
        get() = track != null

    val publishPriority = Track.Priority.STANDARD
}