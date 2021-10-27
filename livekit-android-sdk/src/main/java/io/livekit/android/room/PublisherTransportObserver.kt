package io.livekit.android.room

import io.livekit.android.util.LKLog
import livekit.LivekitRtc
import org.webrtc.*

/**
 * @suppress
 */
class PublisherTransportObserver(
    private val engine: RTCEngine,
    private val client: SignalClient,
) : PeerConnection.Observer, PeerConnectionTransport.Listener {

    var dataChannelListener: ((DataChannel?) -> Unit)? = null
    var iceConnectionChangeListener: ((newState: PeerConnection.IceConnectionState?) -> Unit)? =
        null

    override fun onIceCandidate(iceCandidate: IceCandidate?) {
        val candidate = iceCandidate ?: return
        LKLog.v { "onIceCandidate: $candidate" }
        client.sendCandidate(candidate, target = LivekitRtc.SignalTarget.PUBLISHER)
    }

    override fun onRenegotiationNeeded() {
        engine.negotiate()
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        LKLog.v { "onIceConnection new state: $newState" }
        iceConnectionChangeListener?.invoke(newState)
    }

    override fun onOffer(sd: SessionDescription) {
        client.sendOffer(sd)
    }

    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
    }

    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {
    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
    }

    override fun onAddStream(p0: MediaStream?) {
    }

    override fun onRemoveStream(p0: MediaStream?) {
    }

    override fun onDataChannel(dataChannel: DataChannel?) {
        dataChannelListener?.invoke(dataChannel)
    }

    override fun onTrack(transceiver: RtpTransceiver?) {
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
    }

}