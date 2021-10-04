package io.livekit.android.room.util

import org.webrtc.MediaConstraints

object MediaConstraintKeys {
    const val OFFER_TO_RECV_AUDIO = "OfferToReceiveAudio"
    const val OFFER_TO_RECV_VIDEO = "OfferToReceiveVideo"
    const val ICE_RESTART = "IceRestart"

    const val FALSE = "false"
    const val TRUE = "true"
}

fun MediaConstraints.findConstraint(key: String): String? {
    return mandatory.firstOrNull { it.key == key }?.value
        ?: optional.firstOrNull { it.key == key }?.value
}