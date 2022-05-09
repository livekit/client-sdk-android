package io.livekit.android.videoencodedecode

import android.app.Application
import android.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.webrtc.*
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class)
class Streamer(
    private val useDefaultVideoEncoder: Boolean = false,
    private val codecWhiteList: List<String>? = null,
    application: Application
) {

    val track: VideoTrack
    val peerConnection: PeerConnection

    init {
        val eglBase = EglBase.create()
        val peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        val capturer = DummyVideoCapturer(Color.RED)
        val source = peerConnectionFactory.createVideoSource(false)
        capturer.initialize(
            SurfaceTextureHelper.create("VideoCaptureThread", eglBase.eglBaseContext),
            application,
            source.capturerObserver
        )
        capturer.startCapture(100, 100, 30)
        track = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)
        peerConnection = peerConnectionFactory.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            },
            TrackObserver(onAddTrack = {})
        ) ?: throw NullPointerException()

        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(track.id()),
        )
        peerConnection.addTransceiver(track, transInit)

    }
}