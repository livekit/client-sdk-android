package io.livekit.android.room.track

data class DataTrackOptions(
    val ordered: Boolean = true,
    val maxRetransmitTimeMs: Int = -1,
    val maxRetransmits: Int = -1,
    val name: String
)
