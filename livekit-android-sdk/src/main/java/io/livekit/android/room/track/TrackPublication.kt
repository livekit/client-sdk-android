package io.livekit.android.room.track

import io.livekit.android.room.participant.Participant
import livekit.LivekitModels
import java.lang.ref.WeakReference

open class TrackPublication(info: LivekitModels.TrackInfo, track: Track?, participant: Participant?) {
    var track: Track? = track
        internal set
    var name: String
        internal set
    var sid: String
        private set
    var kind: LivekitModels.TrackType
        private set
    open var muted: Boolean = false
        internal set
    open val subscribed: Boolean
        get() {
            return track != null
        }

    var participant: WeakReference<Participant>;

    init {
        sid = info.sid
        name = info.name
        kind = info.type
        muted = info.muted
        this.participant = WeakReference(participant)
    }

    fun updateFromInfo(info: LivekitModels.TrackInfo) {
        sid = info.sid
        name = info.name
        kind = info.type

        muted = info.muted
    }
}
