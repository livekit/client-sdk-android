package io.livekit.android.mock

import org.webrtc.*

private class MockNativePeerConnectionFactory : NativePeerConnectionFactory {
    override fun createNativePeerConnection(): Long = 0L
}

class MockPeerConnection(
    val observer: PeerConnection.Observer?
) : PeerConnection(MockNativePeerConnectionFactory()) {

    private var closed = false
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

    override fun getSenders(): List<RtpSender> {
        return emptyList()
    }

    override fun getReceivers(): List<RtpReceiver> {
        return emptyList()
    }

    override fun getTransceivers(): List<RtpTransceiver> {
        return emptyList()
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
        track: MediaStreamTrack,
        init: RtpTransceiver.RtpTransceiverInit?
    ): RtpTransceiver {
        return MockRtpTransceiver.create(track, init ?: RtpTransceiver.RtpTransceiverInit())
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
        if (closed) {
            return SignalingState.CLOSED
        }

        if ((localDesc?.type == null && remoteDesc?.type == null) ||
            (localDesc?.type == SessionDescription.Type.OFFER &&
                    remoteDesc?.type == SessionDescription.Type.ANSWER) ||
            (localDesc?.type == SessionDescription.Type.ANSWER &&
                    remoteDesc?.type == SessionDescription.Type.OFFER)
        ) {
            return SignalingState.STABLE
        }

        if (localDesc?.type == SessionDescription.Type.OFFER && remoteDesc?.type == null) {
            return SignalingState.HAVE_LOCAL_OFFER
        }
        if (remoteDesc?.type == SessionDescription.Type.OFFER && localDesc?.type == null) {
            return SignalingState.HAVE_REMOTE_OFFER
        }

        throw IllegalStateException("Illegal signalling state? localDesc: $localDesc, remoteDesc: $remoteDesc")
    }

    private var iceConnectionState = IceConnectionState.NEW
        set(value) {
            if (field != value) {
                field = value
                observer?.onIceConnectionChange(field)

                connectionState = when (field) {
                    IceConnectionState.NEW -> PeerConnectionState.NEW
                    IceConnectionState.CHECKING -> PeerConnectionState.CONNECTING
                    IceConnectionState.CONNECTED,
                    IceConnectionState.COMPLETED -> PeerConnectionState.CONNECTED
                    IceConnectionState.DISCONNECTED -> PeerConnectionState.DISCONNECTED
                    IceConnectionState.FAILED -> PeerConnectionState.FAILED
                    IceConnectionState.CLOSED -> PeerConnectionState.CLOSED
                }
            }
        }

    private var connectionState = PeerConnectionState.NEW
        set(value) {
            if (field != value) {
                field = value
                observer?.onConnectionChange(field)
            }
        }

    override fun iceConnectionState(): IceConnectionState = iceConnectionState

    fun moveToIceConnectionState(newState: IceConnectionState) {
        if (closed && newState != IceConnectionState.CLOSED) {
            throw IllegalArgumentException("peer connection closed, but attempting to move to $newState")
        }

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

    override fun connectionState(): PeerConnectionState = connectionState

    override fun iceGatheringState(): IceGatheringState {
        return super.iceGatheringState()
    }

    override fun close() {
        dispose()
    }

    override fun dispose() {
        iceConnectionState = IceConnectionState.CLOSED
        closed = true
    }

    override fun getNativePeerConnection(): Long = 0L
}