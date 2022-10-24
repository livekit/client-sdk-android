package io.livekit.android.room

import io.livekit.android.MockE2ETest
import io.livekit.android.mock.MockPeerConnection
import io.livekit.android.util.toOkioByteString
import io.livekit.android.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitModels
import livekit.LivekitRtc
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.webrtc.PeerConnection


@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RTCEngineMockE2ETest : MockE2ETest() {

    lateinit var rtcEngine: RTCEngine

    @Before
    fun setupRTCEngine() {
        rtcEngine = component.rtcEngine()
    }

    @Test
    fun iceSubscriberConnect() = runTest {
        connect()
        Assert.assertEquals(
            SignalClientTest.OFFER.offer.sdp,
            rtcEngine.subscriber.peerConnection.remoteDescription.description
        )

        val ws = wsFactory.ws
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(ws.sentRequests[0].toPBByteString())
            .build()

        val subPeerConnection = rtcEngine.subscriber.peerConnection as MockPeerConnection
        val localAnswer = subPeerConnection.localDescription ?: throw IllegalStateException("no answer was created.")
        Assert.assertTrue(sentRequest.hasAnswer())
        Assert.assertEquals(localAnswer.description, sentRequest.answer.sdp)
        Assert.assertEquals(localAnswer.type.canonicalForm(), sentRequest.answer.type)
        Assert.assertEquals(ConnectionState.CONNECTED, rtcEngine.connectionState)
    }

    @Test
    fun reconnectOnFailure() = runTest {
        connect()
        val oldWs = wsFactory.ws
        wsFactory.listener.onFailure(oldWs, Exception(), null)
        testScheduler.advanceTimeBy(1000)
        val newWs = wsFactory.ws
        Assert.assertNotEquals(oldWs, newWs)
    }

    @Test
    fun reconnectOnSubscriberFailure() = runTest {
        connect()
        val oldWs = wsFactory.ws

        val subPeerConnection = rtcEngine.subscriber.peerConnection as MockPeerConnection
        subPeerConnection.moveToIceConnectionState(PeerConnection.IceConnectionState.FAILED)
        testScheduler.advanceTimeBy(1000)

        val newWs = wsFactory.ws
        Assert.assertNotEquals(oldWs, newWs)
    }

    @Test
    fun reconnectOnPublisherFailure() = runTest {
        connect()
        val oldWs = wsFactory.ws

        val pubPeerConnection = rtcEngine.publisher.peerConnection as MockPeerConnection
        pubPeerConnection.moveToIceConnectionState(PeerConnection.IceConnectionState.FAILED)
        testScheduler.advanceTimeBy(1000)

        val newWs = wsFactory.ws
        Assert.assertNotEquals(oldWs, newWs)
    }

    @Test
    fun refreshToken() = runTest {
        connect()

        val oldToken = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_TOKEN)
        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.REFRESH_TOKEN.toOkioByteString())
        wsFactory.listener.onFailure(wsFactory.ws, Exception(), null)

        testScheduler.advanceUntilIdle()
        val newToken = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_TOKEN)
        Assert.assertNotEquals(oldToken, newToken)
        Assert.assertEquals(SignalClientTest.REFRESH_TOKEN.refreshToken, newToken)
    }

    @Test
    fun relayConfiguration() = runTest {
        connect(with(SignalClientTest.JOIN.toBuilder()) {
            join = with(join.toBuilder()) {
                clientConfiguration = with(LivekitModels.ClientConfiguration.newBuilder()) {
                    forceRelay = LivekitModels.ClientConfigSetting.ENABLED
                    build()
                }
                build()
            }
            build()
        })

        val pubPeerConnection = rtcEngine.subscriber.peerConnection as MockPeerConnection
        assertEquals(PeerConnection.IceTransportsType.RELAY, pubPeerConnection.rtcConfig.iceTransportsType)
    }
}