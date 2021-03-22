package io.livekit.android.room.track

import livekit.LivekitModels

open class RemoteTrackPublication(info: LivekitModels.TrackInfo, track: Track? = null) :
    TrackPublication(info, track) {

    val remoteTrack: Track?
        get() = track

    val trackSubscribed: Boolean
        get() = track != null

    val publishPriority = Track.Priority.STANDARD
}