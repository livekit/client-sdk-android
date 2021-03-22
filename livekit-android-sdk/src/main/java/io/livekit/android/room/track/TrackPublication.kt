package io.livekit.android.room.track

import livekit.LivekitModels

open class TrackPublication(info: LivekitModels.TrackInfo, track: Track? = null) {
    var track: Track? = track
        internal set
    var trackName: String
        internal set
    var trackSid: Track.Sid
        private set

    init {
        trackSid = Track.Sid(info.sid)
        trackName = info.name
    }

    fun updateFromInfo(info: LivekitModels.TrackInfo) {
        trackSid = Track.Sid(info.sid)
        trackName = info.name
    }
}