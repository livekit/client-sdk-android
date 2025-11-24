package io.livekit.android.test.mock

import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

class MockPeerConnectionTest {

    lateinit var pc: MockPeerConnection

    @Before
    fun setup() {
        pc = MockPeerConnection(PeerConnection.RTCConfiguration(emptyList()), null)
    }

    @Test
    fun publisherNegotiation() {
        run {
            val observer = mock<SdpObserver>()
            pc.setLocalDescription(
                observer,
                SessionDescription(SessionDescription.Type.OFFER, "local_offer"),
            )
            verify(observer, times(1)).onSetSuccess()
            assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, pc.signalingState())
        }
        run {
            val observer = mock<SdpObserver>()
            pc.setRemoteDescription(
                observer,
                SessionDescription(SessionDescription.Type.ANSWER, "remote_answer"),
            )
            verify(observer, times(1)).onSetSuccess()
            assertEquals(PeerConnection.SignalingState.STABLE, pc.signalingState())
        }
    }

    @Test
    fun subscriberNegotiation() {
        run {
            val observer = mock<SdpObserver>()
            pc.setRemoteDescription(
                observer,
                SessionDescription(SessionDescription.Type.OFFER, "remote_offer"),
            )
            verify(observer, times(1)).onSetSuccess()
            assertEquals(PeerConnection.SignalingState.HAVE_REMOTE_OFFER, pc.signalingState())
        }
        run {
            val observer = mock<SdpObserver>()
            pc.setLocalDescription(
                observer,
                SessionDescription(SessionDescription.Type.ANSWER, "local_answer"),
            )
            verify(observer, times(1)).onSetSuccess()
            assertEquals(PeerConnection.SignalingState.STABLE, pc.signalingState())
        }
    }

    @Test
    fun badNegotiation() {
        run {
            val observer = mock<SdpObserver>()
            pc.setLocalDescription(
                observer,
                SessionDescription(SessionDescription.Type.ANSWER, "local_answer"),
            )
            verify(observer, times(1)).onSetFailure(any())
            assertEquals(PeerConnection.SignalingState.STABLE, pc.signalingState())
        }
    }
}
