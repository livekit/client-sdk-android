package io.livekit.android.room.track

import livekit.LivekitModels

class RemoteDataTrackPublication(
    info: LivekitModels.TrackInfo,
    track: Track? = null
) : RemoteTrackPublication(info, track), DataTrackPublication {
    override val dataTrack: DataTrack?
        get() = track as? DataTrack
}