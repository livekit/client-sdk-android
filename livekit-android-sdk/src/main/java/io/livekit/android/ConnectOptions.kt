package io.livekit.android

import io.livekit.android.room.ProtocolVersion
import org.webrtc.PeerConnection


data class ConnectOptions(
    /** Auto subscribe to room tracks upon connect, defaults to true */
    val autoSubscribe: Boolean = true,

    val iceServers: List<PeerConnection.IceServer>? = null,
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
    val protocolVersion: ProtocolVersion = ProtocolVersion.v8
) {
    internal var reconnect: Boolean = false
}
