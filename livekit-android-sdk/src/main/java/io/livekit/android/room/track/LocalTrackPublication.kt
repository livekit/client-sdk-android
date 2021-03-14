package io.livekit.android.room.track

import livekit.Model

open class LocalTrackPublication(info: Model.TrackInfo, track: Track? = null) :
    TrackPublication(info, track) {
    val localTrack
        get() = track
    var priority = Track.Priority.STANDARD
        private set
}