package io.livekit.android.room.track

import livekit.Model

open class TrackPublication(info: Model.TrackInfo, track: Track? = null) {
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

    fun updateFromInfo(info: Model.TrackInfo) {
        trackSid = Track.Sid(info.sid)
        trackName = info.name
    }
}