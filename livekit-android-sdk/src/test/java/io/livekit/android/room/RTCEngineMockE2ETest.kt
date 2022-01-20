package io.livekit.android.room

import io.livekit.android.MockE2ETest
import io.livekit.android.mock.MockPeerConnection
import io.livekit.android.mock.MockWebSocket
import io.livekit.android.util.LoggingRule
import io.livekit.android.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import livekit.LivekitRtc
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription


@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RTCEngineMockE2ETest : MockE2ETest() {


    @get:Rule
    var loggingRule = LoggingRule()

    lateinit var rtcEngine: RTCEngine

    @Before
    fun setupRTCEngine() {
        rtcEngine = component.rtcEngine()
    }

    @Test
    fun iceSubscriberConnect() = runBlockingTest {
        connect()

        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, "remote_offer")
        rtcEngine.onOffer(remoteOffer)

        Assert.assertEquals(remoteOffer, rtcEngine.subscriber.peerConnection.remoteDescription)

        val ws = wsFactory.ws as MockWebSocket
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(ws.sentRequests[0].toPBByteString())
            .build()

        val subPeerConnection = rtcEngine.subscriber.peerConnection as MockPeerConnection
        val localAnswer = subPeerConnection.localDescription ?: throw IllegalStateException("no answer was created.")
        Assert.assertTrue(sentRequest.hasAnswer())
        Assert.assertEquals(localAnswer.description, sentRequest.answer.sdp)
        Assert.assertEquals(localAnswer.type.canonicalForm(), sentRequest.answer.type)

        subPeerConnection.moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)

        Assert.assertEquals(ConnectionState.CONNECTED, rtcEngine.connectionState)
    }

    @Test
    fun reconnectOnFailure() = runBlockingTest {
        connect()
        val oldWs = wsFactory.ws
        wsFactory.listener.onFailure(oldWs, Exception(), null)

        val newWs = wsFactory.ws
        Assert.assertNotEquals(oldWs, newWs)
    }
}