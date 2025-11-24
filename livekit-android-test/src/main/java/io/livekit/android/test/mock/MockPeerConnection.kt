/*
 * Copyright 2023-2025 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.test.mock

import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.MockRtpTransceiver
import livekit.org.webrtc.NativePeerConnectionFactory
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RTCStatsCollectorCallback
import livekit.org.webrtc.RTCStatsReport
import livekit.org.webrtc.RtcCertificatePem
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpSender
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.StatsObserver

private class MockNativePeerConnectionFactory : NativePeerConnectionFactory {
    override fun createNativePeerConnection(): Long = 0L
}

class MockPeerConnection(
    var rtcConfig: RTCConfiguration,
    val observer: Observer?,
) : PeerConnection(MockNativePeerConnectionFactory()) {

    private var closed = false
    var localDesc: SessionDescription? = null
    var remoteDesc: SessionDescription? = null

    private val signalStateMachine = SignalStateMachine { newState ->
        observer?.onSignalingChange(newState)
    }

    private val transceivers = mutableListOf<RtpTransceiver>()

    override fun getLocalDescription(): SessionDescription? = localDesc
    override fun setLocalDescription(observer: SdpObserver, sdp: SessionDescription) {
        if (sdp.description.isEmpty()) {
            observer.onSetFailure("empty local description")
            return
        }

        try {
            signalStateMachine.handleSetMethod(Location.LOCAL, sdp.type)
        } catch (e: IllegalStateException) {
            observer.onSetFailure(e.message)
        }

        localDesc = sdp
        observer.onSetSuccess()

        if (signalingState() == SignalingState.STABLE) {
            moveToIceConnectionState(IceConnectionState.CONNECTED)
        }
    }

    override fun getRemoteDescription(): SessionDescription? = remoteDesc
    override fun setRemoteDescription(observer: SdpObserver, sdp: SessionDescription) {
        if (sdp.description.isEmpty()) {
            observer.onSetFailure("empty remote description")
            return
        }

        try {
            signalStateMachine.handleSetMethod(Location.REMOTE, sdp.type)
        } catch (e: IllegalStateException) {
            observer.onSetFailure(e.message)
        }

        remoteDesc = sdp
        observer.onSetSuccess()

        if (signalingState() == SignalingState.STABLE) {
            moveToIceConnectionState(IceConnectionState.CONNECTED)
        }
    }

    override fun getCertificate(): RtcCertificatePem? {
        return null
    }

    val dataChannels = mutableMapOf<String, DataChannel>()
    override fun createDataChannel(label: String?, init: DataChannel.Init?): DataChannel {
        val dataChannel = MockDataChannel(label)
        if (label != null) {
            dataChannels[label] = dataChannel
        }
        return dataChannel
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

    override fun setConfiguration(config: RTCConfiguration): Boolean {
        this.rtcConfig = config
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
        return transceivers
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

    override fun addTransceiver(track: MediaStreamTrack): RtpTransceiver {
        val transceiver = MockRtpTransceiver.create(track, RtpTransceiver.RtpTransceiverInit())
        transceivers.add(transceiver)
        return transceiver
    }

    override fun addTransceiver(
        track: MediaStreamTrack,
        init: RtpTransceiver.RtpTransceiverInit?,
    ): RtpTransceiver {
        val transceiver = MockRtpTransceiver.create(track, init ?: RtpTransceiver.RtpTransceiverInit())
        transceivers.add(transceiver)
        return transceiver
    }

    override fun addTransceiver(mediaType: MediaStreamTrack.MediaType?): RtpTransceiver {
        return super.addTransceiver(mediaType)
    }

    override fun addTransceiver(
        mediaType: MediaStreamTrack.MediaType?,
        init: RtpTransceiver.RtpTransceiverInit?,
    ): RtpTransceiver {
        return super.addTransceiver(mediaType, init)
    }

    @Deprecated("Deprecated in Java")
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

    override fun signalingState(): SignalingState = signalStateMachine.state

    private var iceConnectionState = IceConnectionState.NEW
        set(value) {
            if (field != value) {
                field = value
                observer?.onIceConnectionChange(field)

                connectionState = when (field) {
                    IceConnectionState.NEW -> PeerConnectionState.NEW
                    IceConnectionState.CHECKING -> PeerConnectionState.CONNECTING
                    IceConnectionState.CONNECTED,
                    IceConnectionState.COMPLETED,
                    -> PeerConnectionState.CONNECTED

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
            IceConnectionState.COMPLETED,
            -> {
                val currentOrdinal = iceConnectionState.ordinal
                val newOrdinal = newState.ordinal

                if (currentOrdinal < newOrdinal) {
                    // Ensure that we move through each state.
                    for (ordinal in ((currentOrdinal + 1)..newOrdinal)) {
                        iceConnectionState = IceConnectionState.entries[ordinal]
                    }
                } else {
                    iceConnectionState = newState
                }
            }

            IceConnectionState.FAILED,
            IceConnectionState.DISCONNECTED,
            IceConnectionState.CLOSED,
            -> {
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
        signalStateMachine.close()

        transceivers.forEach { t -> t.dispose() }
        transceivers.clear()
    }

    override fun getNativePeerConnection(): Long = 0L
}


private class SignalStateMachine(
    var changeListener: ((PeerConnection.SignalingState) -> Unit)? = null,
) {
    var state = PeerConnection.SignalingState.STABLE
        set(value) {
            val changed = field != value
            field = value
            if (changed) {
                changeListener?.invoke(field)
            }
        }

    /**
     * Throws if would go to invalid state.
     *
     * Does not handle PRANSWER or ROLLBACK.
     *
     * State machine as shown here:
     * https://w3c.github.io/webrtc-pc/#fig-non-normative-signaling-state-transitions-diagram-method-calls-abbreviated
     */
    @Throws(IllegalStateException::class)
    fun handleSetMethod(location: Location, type: SessionDescription.Type) {

        fun throwException() {
            throw IllegalStateException("Illegal set of $location with $type on signal state $state")
        }
        when (state) {
            PeerConnection.SignalingState.STABLE -> {
                // Can only accept offers from stable
                if (type != SessionDescription.Type.OFFER) {
                    throwException()
                }
                state = when (location) {
                    Location.LOCAL -> PeerConnection.SignalingState.HAVE_LOCAL_OFFER
                    Location.REMOTE -> PeerConnection.SignalingState.HAVE_REMOTE_OFFER
                }
            }

            PeerConnection.SignalingState.HAVE_LOCAL_OFFER -> {
                if (location == Location.LOCAL && type == SessionDescription.Type.OFFER) {
                    // legal, does not change state.
                } else if (location == Location.REMOTE && type == SessionDescription.Type.ANSWER) {
                    state = PeerConnection.SignalingState.STABLE
                } else {
                    throwException()
                }
            }

            PeerConnection.SignalingState.HAVE_REMOTE_OFFER -> {
                if (location == Location.REMOTE && type == SessionDescription.Type.OFFER) {
                    // legal, does not change state.
                } else if (location == Location.LOCAL && type == SessionDescription.Type.ANSWER) {
                    state = PeerConnection.SignalingState.STABLE
                } else {
                    throwException()
                }
            }

            PeerConnection.SignalingState.HAVE_LOCAL_PRANSWER -> TODO()
            PeerConnection.SignalingState.HAVE_REMOTE_PRANSWER -> TODO()
            PeerConnection.SignalingState.CLOSED -> {
                throw IllegalStateException("Closed")
            }
        }
    }

    fun close() {
        state = PeerConnection.SignalingState.CLOSED
    }
}

private enum class Location {
    LOCAL,
    REMOTE;
}
