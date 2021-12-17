package io.livekit.android.room

import io.livekit.android.util.LKLog
import livekit.LivekitRtc
import org.webrtc.*

/**
 * @suppress
 */
class SubscriberTransportObserver(
    private val engine: RTCEngine,
    private val client: SignalClient,
) : PeerConnection.Observer {

    var dataChannelListener: ((DataChannel) -> Unit)? = null
    var iceConnectionChangeListener: ((PeerConnection.IceConnectionState?) -> Unit)? = null

    override fun onIceCandidate(candidate: IceCandidate) {
        LKLog.v { "onIceCandidate: $candidate" }
        client.sendCandidate(candidate, LivekitRtc.SignalTarget.SUBSCRIBER)
    }

    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
        val track = receiver.track() ?: return
        LKLog.v { "onAddTrack: ${track.kind()}, ${track.id()}, ${streams.fold("") { sum, it -> "$sum, $it" }}" }
        engine.listener?.onAddTrack(track, streams)
    }

    override fun onTrack(transceiver: RtpTransceiver) {
        when (transceiver.mediaType) {
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> LKLog.v { "peerconn started receiving audio" }
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> LKLog.v { "peerconn started receiving video" }
            else -> LKLog.d { "peerconn started receiving unknown media type: ${transceiver.mediaType}" }
        }
    }

    override fun onDataChannel(channel: DataChannel) {
        LKLog.e { "onDataChannel: ${channel.label()}" }
        dataChannelListener?.invoke(channel)
    }

    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
        LKLog.v { "onConnectionChange new state: $newState" }
    }

    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        LKLog.v { "onIceConnection new state: $newState" }
        iceConnectionChangeListener?.invoke(newState)
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

    override fun onRenegotiationNeeded() {
    }

}