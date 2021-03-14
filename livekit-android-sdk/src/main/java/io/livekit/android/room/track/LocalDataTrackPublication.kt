package io.livekit.android.room.track

import livekit.Model

class LocalDataTrackPublication(info: Model.TrackInfo, track: Track? = null) :
    LocalTrackPublication(info, track), DataTrackPublication {
    override val dataTrack: DataTrack?
        get() = track as? DataTrack
}