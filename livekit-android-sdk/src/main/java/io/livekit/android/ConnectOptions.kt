package io.livekit.android

import io.livekit.android.room.ProtocolVersion
import org.webrtc.PeerConnection


data class ConnectOptions(
    /** Auto subscribe to room tracks upon connect, defaults to true */
    val autoSubscribe: Boolean = true,

    /**
     * A user-provided list of ice servers. This will be merged into
     * the ice servers in [rtcConfig] if it is also provided.
     */
    val iceServers: List<PeerConnection.IceServer>? = null,

    /**
     * A user-provided RTCConfiguration to override options.
     *
     * Note: LiveKit requires [PeerConnection.SdpSemantics.UNIFIED_PLAN] and
     * [PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY].
     */
    val rtcConfig: PeerConnection.RTCConfiguration? = null,
    /**
     * capture and publish audio track on connect, defaults to false
     */
    val audio: Boolean = false,
    /**
     * capture and publish video track on connect, defaults to false
     */
    val video: Boolean = false,

    /**
     * the protocol version to use with the server.
     */
    val protocolVersion: ProtocolVersion = ProtocolVersion.v9
) {
    internal var reconnect: Boolean = false
    internal var participantSid: String? = null
}
