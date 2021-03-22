package io.livekit.android.room.track

import livekit.LivekitModels

open class LocalTrackPublication(info: LivekitModels.TrackInfo, track: Track? = null) :
    TrackPublication(info, track) {
    val localTrack
        get() = track
    var priority = Track.Priority.STANDARD
        private set
}