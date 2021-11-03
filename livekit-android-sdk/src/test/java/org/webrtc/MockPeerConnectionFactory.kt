package org.webrtc

import io.livekit.android.mock.MockPeerConnection

class MockPeerConnectionFactory : PeerConnectionFactory(1L) {
    override fun createPeerConnectionInternal(
        rtcConfig: PeerConnection.RTCConfiguration?,
        constraints: MediaConstraints?,
        observer: PeerConnection.Observer?,
        sslCertificateVerifier: SSLCertificateVerifier?
    ): PeerConnection {
        return MockPeerConnection(observer)
    }
}