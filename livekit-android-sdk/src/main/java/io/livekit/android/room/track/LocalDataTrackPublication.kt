package io.livekit.android.room.track

import livekit.LivekitModels

class LocalDataTrackPublication(info: LivekitModels.TrackInfo, track: Track? = null) :
    LocalTrackPublication(info, track), DataTrackPublication {
    override val dataTrack: DataTrack?
        get() = track as? DataTrack
}