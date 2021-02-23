package io.livekit.android.room

import org.webrtc.PeerConnection
import javax.inject.Inject

class RTCEngine
@Inject
constructor(
    private val client: RTCClient
) {

    init {
        val rtcConfig = PeerConnection.RTCConfiguration(RTCClient.DEFAULT_ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

    }

    suspend fun join(url: String, token: String, isSecure: Boolean) {
        client.join(url, token, isSecure)
    }
}