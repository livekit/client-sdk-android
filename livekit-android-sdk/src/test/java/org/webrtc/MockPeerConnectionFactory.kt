package org.webrtc

import io.livekit.android.mock.MockPeerConnection
import io.livekit.android.mock.MockVideoSource
import io.livekit.android.mock.MockVideoStreamTrack

class MockPeerConnectionFactory : PeerConnectionFactory(1L) {
    override fun createPeerConnectionInternal(
        rtcConfig: PeerConnection.RTCConfiguration,
        constraints: MediaConstraints?,
        observer: PeerConnection.Observer?,
        sslCertificateVerifier: SSLCertificateVerifier?
    ): PeerConnection {
        return MockPeerConnection(rtcConfig, observer)
    }

    override fun createVideoSource(isScreencast: Boolean, alignTimestamps: Boolean): VideoSource {
        return MockVideoSource()
    }

    override fun createVideoSource(isScreencast: Boolean): VideoSource {
        return MockVideoSource()
    }

    override fun createVideoTrack(id: String, source: VideoSource?): VideoTrack {
        return MockVideoStreamTrack(id = id)
    }

    override fun getRtpSenderCapabilities(mediaType: MediaStreamTrack.MediaType): RtpCapabilities {
        return RtpCapabilities(
            listOf(
                RtpCapabilities.CodecCapability().apply {
                    name = "VP8"
                    mimeType = "video/VP8"
                    kind = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                    parameters = emptyMap()
                },
                RtpCapabilities.CodecCapability().apply {
                    name = "H264"
                    mimeType = "video/H264"
                    kind = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                    parameters = mapOf("profile-level-id" to "640c1f")
                },
                RtpCapabilities.CodecCapability().apply {
                    name = "H264"
                    mimeType = "video/H264"
                    kind = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                    parameters = mapOf("profile-level-id" to "42e01f")
                },
            ),
            emptyList()
        )
    }
}