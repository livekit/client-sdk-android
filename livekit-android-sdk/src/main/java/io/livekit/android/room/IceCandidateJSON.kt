package io.livekit.android.room

import kotlinx.serialization.Serializable

/**
 * @suppress
 */
@Serializable
data class IceCandidateJSON(val candidate: String, val sdpMLineIndex: Int, val sdpMid: String?)