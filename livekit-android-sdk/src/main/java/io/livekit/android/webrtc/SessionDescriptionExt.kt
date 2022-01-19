package io.livekit.android.webrtc

import livekit.LivekitRtc
import org.webrtc.SessionDescription

fun SessionDescription.toProtoSessionDescription(): LivekitRtc.SessionDescription {
    val sdBuilder = LivekitRtc.SessionDescription.newBuilder()
    sdBuilder.sdp = description
    sdBuilder.type = type.canonicalForm()

    return sdBuilder.build()
}