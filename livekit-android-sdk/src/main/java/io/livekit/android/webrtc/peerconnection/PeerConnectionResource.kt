package io.livekit.android.webrtc.peerconnection

import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver

/**
 * Objects obtained through [PeerConnection] are transient,
 * and should not be kept in memory. Calls to these methods
 * dispose all existing objects in the tree and refresh with
 * new updated objects:
 *
 * * [PeerConnection.getTransceivers]
 * * [PeerConnection.getReceivers]
 * * [PeerConnection.getSenders]
 *
 * For this reason, any object gotten through the PeerConnection
 * should instead be looked up through the PeerConnection as needed.
 */
internal abstract class PeerConnectionResource<T>(val parentPeerConnection: PeerConnection) {
    abstract fun get(): T?
}

internal class RtpTransceiverResource(parentPeerConnection: PeerConnection, private val senderId: String) : PeerConnectionResource<RtpTransceiver>(parentPeerConnection) {
    override fun get() = executeOnRTCThread {
        parentPeerConnection.transceivers.firstOrNull { t -> t.sender.id() == senderId }
    }
}

internal class RtpReceiverResource(parentPeerConnection: PeerConnection, private val receiverId: String) : PeerConnectionResource<RtpReceiver>(parentPeerConnection) {
    override fun get() = executeOnRTCThread {
        parentPeerConnection.receivers.firstOrNull { r -> r.id() == receiverId }
    }
}

internal class RtpSenderResource(parentPeerConnection: PeerConnection, private val senderId: String) : PeerConnectionResource<RtpSender>(parentPeerConnection) {
    override fun get() = executeOnRTCThread {
        parentPeerConnection.senders.firstOrNull { s -> s.id() == senderId }
    }
}
