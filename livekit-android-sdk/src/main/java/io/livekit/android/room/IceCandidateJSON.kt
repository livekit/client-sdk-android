package io.livekit.android.room

import kotlinx.serialization.Serializable

@Serializable
data class IceCandidateJSON(val sdp: String, val sdpMLineIndex: Int, val sdpMid: String?)