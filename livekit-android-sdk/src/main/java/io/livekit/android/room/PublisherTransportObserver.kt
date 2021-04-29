package io.livekit.android.room

import com.github.ajalt.timberkt.Timber
import livekit.LivekitRtc
import org.webrtc.*

/**
 * @suppress
 */
class PublisherTransportObserver(
    private val engine: RTCEngine
) : PeerConnection.Observer {

    override fun onIceCandidate(iceCandidate: IceCandidate?) {
        val candidate = iceCandidate ?: return
        engine.client.sendCandidate(candidate, target = LivekitRtc.SignalTarget.PUBLISHER)
    }

    override fun onRenegotiationNeeded() {
        engine.negotiate()
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        val state = newState ?: throw NullPointerException("unexpected null new state, what do?")
        Timber.v { "onIceConnection new state: $newState" }
        if (state == PeerConnection.IceConnectionState.CONNECTED) {
            engine.iceState = IceState.CONNECTED
        } else if (state == PeerConnection.IceConnectionState.FAILED) {
            // when we publish tracks, some WebRTC versions will send out disconnected events periodically
            engine.iceState = IceState.DISCONNECTED
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