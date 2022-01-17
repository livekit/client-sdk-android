package io.livekit.android.mock

import org.webrtc.*

private class MockNativePeerConnectionFactory : NativePeerConnectionFactory {
    override fun createNativePeerConnection(): Long = 0L
}

class MockPeerConnection(
    val observer: PeerConnection.Observer?
) : PeerConnection(MockNativePeerConnectionFactory()) {

    var localDesc: SessionDescription? = null
    var remoteDesc: SessionDescription? = null
    override fun getLocalDescription(): SessionDescription? = localDesc
    override fun setLocalDescription(observer: SdpObserver?, sdp: SessionDescription?) {
        localDesc = sdp
        observer?.onSetSuccess()
    }

    override fun getRemoteDescription(): SessionDescription? = remoteDesc
    override fun setRemoteDescription(observer: SdpObserver?, sdp: SessionDescription?) {
        remoteDesc = sdp
        observer?.onSetSuccess()
    }

    override fun getCertificate(): RtcCertificatePem? {
        return null
    }

    override fun createDataChannel(label: String?, init: DataChannel.Init?): DataChannel {
        return MockDataChannel(label)
    }

    override fun createOffer(observer: SdpObserver?, constraints: MediaConstraints?) {
        val sdp = SessionDescription(SessionDescription.Type.OFFER, "local_offer")
        observer?.onCreateSuccess(sdp)
    }

    override fun createAnswer(observer: SdpObserver?, constraints: MediaConstraints?) {
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, "local_answer")
        observer?.onCreateSuccess(sdp)
    }


    override fun setAudioPlayout(playout: Boolean) {
    }

    override fun setAudioRecording(recording: Boolean) {
    }

    override fun setConfiguration(config: RTCConfiguration?): Boolean {
        return true
    }

    override fun addIceCandidate(candidate: IceCandidate?): Boolean {
        return true
    }

    override fun removeIceCandidates(candidates: Array<out IceCandidate>?): Boolean {
        return true
    }

    override fun addStream(stream: MediaStream?): Boolean {
        return super.addStream(stream)
    }

    override fun removeStream(stream: MediaStream?) {
        super.removeStream(stream)
    }

    override fun createSender(kind: String?, stream_id: String?): RtpSender {
        return super.createSender(kind, stream_id)
    }

    override fun getSenders(): MutableList<RtpSender> {
        return super.getSenders()
    }

    override fun getReceivers(): MutableList<RtpReceiver> {
        return super.getReceivers()
    }

    override fun getTransceivers(): MutableList<RtpTransceiver> {
        return super.getTransceivers()
    }

    override fun addTrack(track: MediaStreamTrack?): RtpSender {
        return super.addTrack(track)
    }

    override fun addTrack(track: MediaStreamTrack?, streamIds: MutableList<String>?): RtpSender {
        return super.addTrack(track, streamIds)
    }

    override fun removeTrack(sender: RtpSender?): Boolean {
        return super.removeTrack(sender)
    }

    override fun addTransceiver(track: MediaStreamTrack?): RtpTransceiver {
        return super.addTransceiver(track)
    }

    override fun addTransceiver(
        track: MediaStreamTrack?,
        init: RtpTransceiver.RtpTransceiverInit?
    ): RtpTransceiver {
        return super.addTransceiver(track, init)
    }

    override fun addTransceiver(mediaType: MediaStreamTrack.MediaType?): RtpTransceiver {
        return super.addTransceiver(mediaType)
    }

    override fun addTransceiver(
        mediaType: MediaStreamTrack.MediaType?,
        init: RtpTransceiver.RtpTransceiverInit?
    ): RtpTransceiver {
        return super.addTransceiver(mediaType, init)
    }

    override fun getStats(observer: StatsObserver?, track: MediaStreamTrack?): Boolean {
        observer?.onComplete(emptyArray())
        return true
    }

    override fun getStats(callback: RTCStatsCollectorCallback?) {
        callback?.onStatsDelivered(RTCStatsReport(0, emptyMap()))
    }

    override fun setBitrate(min: Int?, current: Int?, max: Int?): Boolean {
        return true
    }

    override fun startRtcEventLog(file_descriptor: Int, max_size_bytes: Int): Boolean {
        return true
    }

    override fun stopRtcEventLog() {
    }

    override fun signalingState(): SignalingState {
        return super.signalingState()
    }

    private var iceConnectionState = IceConnectionState.NEW
        set(value) {
            if (field != value) {
                field = value
                observer?.onIceConnectionChange(field)
            }
        }

    override fun iceConnectionState(): IceConnectionState = iceConnectionState

    fun moveToIceConnectionState(newState: IceConnectionState) {
        when (newState) {
            IceConnectionState.NEW,
            IceConnectionState.CHECKING,
            IceConnectionState.CONNECTED,
            IceConnectionState.COMPLETED -> {
                val currentOrdinal = iceConnectionState.ordinal
                val newOrdinal = newState.ordinal

                if (currentOrdinal < newOrdinal) {
                    // Ensure that we move through each state.
                    for (ordinal in ((currentOrdinal + 1)..newOrdinal)) {
                        iceConnectionState = IceConnectionState.values()[ordinal]
                    }
                } else {
                    iceConnectionState = newState
                }
            }
            IceConnectionState.FAILED,
            IceConnectionState.DISCONNECTED,
            IceConnectionState.CLOSED -> {
                // jump to state directly.
                iceConnectionState = newState
            }
        }
    }

    override fun connectionState(): PeerConnectionState {
        return super.connectionState()
    }

    override fun iceGatheringState(): IceGatheringState {
        return super.iceGatheringState()
    }

    override fun close() {
    }

    override fun dispose() {
    }

    override fun getNativePeerConnection(): Long = 0L
}