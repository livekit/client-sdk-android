package io.livekit.android.room.track

import livekit.LivekitModels

open class TrackPublication(info: LivekitModels.TrackInfo, track: Track? = null) {
    var track: Track? = track
        internal set
    var name: String
        internal set
    var sid: String
        private set
    var kind: LivekitModels.TrackType
        private set
    var muted: Boolean
        private set

    init {
        sid = info.sid
        name = info.name
        kind = info.type
        muted = info.muted
    }

    fun updateFromInfo(info: LivekitModels.TrackInfo) {
        sid = info.sid
        name = info.name
        kind = info.type

        // TODO: forward mute status to listener
        muted = info.muted
    }
}
