package io.livekit.android.room.track

import livekit.Model

class RemoteDataTrackPublication(
    info: Model.TrackInfo,
    track: Track? = null
) : RemoteTrackPublication(info, track), DataTrackPublication {
    override val dataTrack: DataTrack?
        get() = track as? DataTrack
}