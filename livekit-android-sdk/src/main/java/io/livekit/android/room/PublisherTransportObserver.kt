package io.livekit.android.room

import livekit.Rtc
import org.webrtc.*

class PublisherTransportObserver(
    private val engine: RTCEngine
) : PeerConnection.Observer {

    override fun onIceCandidate(iceCandidate: IceCandidate?) {
        val candidate = iceCandidate ?: return
        if (engine.rtcConnected) {
            engine.client.sendCandidate(candidate, target = Rtc.SignalTarget.PUBLISHER)
        } else {
            engine.pendingCandidates.add(candidate)
        }
    }

    override fun onRenegotiationNeeded() {
        engine.negotiate()
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        val state = newState ?: throw NullPointerException("unexpected null new state, what do?")
        if (state == PeerConnection.IceConnectionState.CONNECTED && !engine.iceConnected) {
            engine.iceConnected = true
        } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
            engine.iceConnected = false
            engine.listener?.onDisconnect("Peer connection disconnected")
        }
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

    override fun onDataChannel(p0: DataChannel?) {
    }

    override fun onTrack(transceiver: RtpTransceiver?) {
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
    }
}