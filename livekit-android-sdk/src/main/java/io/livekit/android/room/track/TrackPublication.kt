package io.livekit.android.room.track

import io.livekit.android.room.participant.Participant
import livekit.LivekitModels
import java.lang.ref.WeakReference

open class TrackPublication(
    info: LivekitModels.TrackInfo,
    track: Track?,
    participant: Participant
) {
    var track: Track? = track
        internal set
    var name: String
        internal set
    var sid: String
        private set
    var kind: Track.Kind
        private set
    open var muted: Boolean = false
        internal set
    open val subscribed: Boolean
        get() {
            return track != null
        }

    var participant: WeakReference<Participant>

    init {
        sid = info.sid
        name = info.name
        kind = Track.Kind.fromProto(info.type)
        this.participant = WeakReference(participant)
        muted = info.muted
    }

    fun updateFromInfo(info: LivekitModels.TrackInfo) {
        sid = info.sid
        name = info.name
        kind = Track.Kind.fromProto(info.type)
        muted = info.muted
    }
}
