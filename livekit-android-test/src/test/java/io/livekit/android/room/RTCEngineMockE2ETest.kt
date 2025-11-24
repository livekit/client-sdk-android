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

package io.livekit.android.room

import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.events.FlowCollector
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.SignalRequestHandler
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.flow
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.org.webrtc.PeerConnection
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RTCEngineMockE2ETest : MockE2ETest() {

    lateinit var rtcEngine: RTCEngine

    @Before
    fun setupRTCEngine() {
        rtcEngine = component.rtcEngine()
    }

    @Test
    fun connectionState() = runTest {
        val collector = FlowCollector(rtcEngine::connectionState.flow, coroutineRule.scope)
        connect()
        val events = collector.stopCollecting()
        println(events)
        assertEquals(3, events.size)
        assertEquals(ConnectionState.DISCONNECTED, events[0])
        assertEquals(ConnectionState.CONNECTING, events[1])
        assertEquals(ConnectionState.CONNECTED, events[2])
    }

    @Test
    fun iceServersSetOnJoin() = runTest {
        connect()
        val sentIceServers = TestData.JOIN.join.iceServersList
            .map { it.toWebrtc() }
        val subPeerConnection = getSubscriberPeerConnection()

        assertEquals(sentIceServers, subPeerConnection.rtcConfig.iceServers)
    }

    @Test
    fun iceSubscriberConnect() = runTest {
        connect()
        assertEquals(
            TestData.OFFER.offer.sdp,
            getSubscriberPeerConnection().remoteDescription?.description,
        )

        val ws = wsFactory.ws
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(ws.sentRequests[0].toPBByteString())
            .build()

        val subPeerConnection = getSubscriberPeerConnection()
        val localAnswer = subPeerConnection.localDescription ?: throw IllegalStateException("no answer was created.")
        assertTrue(sentRequest.hasAnswer())
        assertEquals(localAnswer.description, sentRequest.answer.sdp)
        assertEquals(localAnswer.type.canonicalForm(), sentRequest.answer.type)
        assertEquals(ConnectionState.CONNECTED, rtcEngine.connectionState)
        assertEquals(TestData.OFFER.offer.id, sentRequest.answer.id) // Offer id must match answer id
    }

    @Test
    fun icePublisherConnect() = runTest {
        connect()

        val ws = wsFactory.ws
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasOffer()) {
                val answer = with(LivekitRtc.SignalResponse.newBuilder()) {
                    answer = with(LivekitRtc.SessionDescription.newBuilder()) {
                        sdp = "remote_answer"
                        type = "answer"
                        id = request.offer.id
                        build()
                    }
                    build()
                }
                wsFactory.receiveMessage(answer)
                true
            }
            false
        }

        val publisher = rtcEngine.getPublisherPeerConnection() as MockPeerConnection

        ws.clearRequests()
        publisher.observer?.onRenegotiationNeeded()
        advanceUntilIdle()

        assertEquals(1, ws.sentRequests.size)
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(ws.sentRequests[0].toPBByteString())
            .build()

        assertTrue(sentRequest.hasOffer())

        assertEquals("local_offer", sentRequest.offer.sdp)
        assertEquals("offer", sentRequest.offer.type)
        assertEquals(1, sentRequest.offer.id) // Offer id must match answer id
        assertEquals(PeerConnection.SignalingState.STABLE, publisher.signalingState())
        assertEquals(PeerConnection.PeerConnectionState.CONNECTED, publisher.connectionState())
    }

    @Test
    fun multiplePublisherOffersIncrementsIds() = runTest {
        connect()

        val ws = wsFactory.ws
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasOffer()) {
                val answer = with(LivekitRtc.SignalResponse.newBuilder()) {
                    answer = with(LivekitRtc.SessionDescription.newBuilder()) {
                        sdp = "remote_answer"
                        type = "answer"
                        id = request.offer.id
                        build()
                    }
                    build()
                }
                wsFactory.receiveMessage(answer)
                true
            }
            false
        }

        val publisher = rtcEngine.getPublisherPeerConnection() as MockPeerConnection

        for (i in 1..3) {
            ws.clearRequests()
            publisher.observer?.onRenegotiationNeeded()
            advanceUntilIdle()

            assertEquals(1, ws.sentRequests.size)
            val sentRequest = LivekitRtc.SignalRequest.newBuilder()
                .mergeFrom(ws.sentRequests[0].toPBByteString())
                .build()

            assertTrue(sentRequest.hasOffer())

            assertEquals("local_offer", sentRequest.offer.sdp)
            assertEquals("offer", sentRequest.offer.type)
            assertEquals(i, sentRequest.offer.id) // Offer id must match answer id
            assertEquals(PeerConnection.SignalingState.STABLE, publisher.signalingState())
            assertEquals(PeerConnection.PeerConnectionState.CONNECTED, publisher.connectionState())
        }
    }

    @Test
    fun offerIdMismatchIsIgnored() = runTest {
        connect()

        val goodHandler: SignalRequestHandler = { request ->
            if (request.hasOffer()) {
                val answer = with(LivekitRtc.SignalResponse.newBuilder()) {
                    answer = with(LivekitRtc.SessionDescription.newBuilder()) {
                        sdp = "remote_answer"
                        type = "answer"
                        id = request.offer.id
                        build()
                    }
                    build()
                }
                wsFactory.receiveMessage(answer)
                true
            }
            false
        }
        wsFactory.registerSignalRequestHandler(goodHandler)

        val publisher = rtcEngine.getPublisherPeerConnection() as MockPeerConnection
        publisher.observer?.onRenegotiationNeeded()
        advanceUntilIdle()
        assertEquals(PeerConnection.SignalingState.STABLE, publisher.signalingState())
        wsFactory.unregisterSignalRequestHandler(goodHandler)

        val oldIdHandler: SignalRequestHandler = { request ->
            if (request.hasOffer()) {
                val answer = with(LivekitRtc.SignalResponse.newBuilder()) {
                    answer = with(LivekitRtc.SessionDescription.newBuilder()) {
                        sdp = "remote_answer"
                        type = "answer"
                        id = 1
                        build()
                    }
                    build()
                }
                wsFactory.receiveMessage(answer)
                true
            }
            false
        }
        wsFactory.registerSignalRequestHandler(oldIdHandler)
        publisher.observer?.onRenegotiationNeeded()
        advanceUntilIdle()

        // Answer with old id must be ignored
        assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, publisher.signalingState())
        wsFactory.unregisterSignalRequestHandler(goodHandler)
    }

    @Test
    fun offerIdMismatchButZeroIsAccepted() = runTest {
        connect()

        val goodHandler: SignalRequestHandler = { request ->
            if (request.hasOffer()) {
                val answer = with(LivekitRtc.SignalResponse.newBuilder()) {
                    answer = with(LivekitRtc.SessionDescription.newBuilder()) {
                        sdp = "remote_answer"
                        type = "answer"
                        id = request.offer.id
                        build()
                    }
                    build()
                }
                wsFactory.receiveMessage(answer)
                true
            }
            false
        }
        wsFactory.registerSignalRequestHandler(goodHandler)

        val publisher = rtcEngine.getPublisherPeerConnection() as MockPeerConnection
        publisher.observer?.onRenegotiationNeeded()
        advanceUntilIdle()
        assertEquals(PeerConnection.SignalingState.STABLE, publisher.signalingState())
        wsFactory.unregisterSignalRequestHandler(goodHandler)

        val oldIdHandler: SignalRequestHandler = { request ->
            if (request.hasOffer()) {
                val answer = with(LivekitRtc.SignalResponse.newBuilder()) {
                    answer = with(LivekitRtc.SessionDescription.newBuilder()) {
                        sdp = "remote_answer"
                        type = "answer"
                        id = 0
                        build()
                    }
                    build()
                }
                wsFactory.receiveMessage(answer)
                true
            }
            false
        }
        wsFactory.registerSignalRequestHandler(oldIdHandler)
        publisher.observer?.onRenegotiationNeeded()
        advanceUntilIdle()

        // Answer with zero id must be accepted
        assertEquals(PeerConnection.SignalingState.STABLE, publisher.signalingState())
        wsFactory.unregisterSignalRequestHandler(goodHandler)
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

        val subPeerConnection = getSubscriberPeerConnection()
        subPeerConnection.moveToIceConnectionState(PeerConnection.IceConnectionState.FAILED)
        testScheduler.advanceTimeBy(1000)

        val newWs = wsFactory.ws
        Assert.assertNotEquals(oldWs, newWs)
    }

    @Test
    fun reconnectOnPublisherFailure() = runTest {
        connect()
        val oldWs = wsFactory.ws

        val pubPeerConnection = getPublisherPeerConnection()
        pubPeerConnection.moveToIceConnectionState(PeerConnection.IceConnectionState.FAILED)
        testScheduler.advanceTimeBy(1000)

        val newWs = wsFactory.ws
        Assert.assertNotEquals(oldWs, newWs)
    }

    @Test
    fun refreshToken() = runTest {
        connect()

        val oldToken = wsFactory.request.header("Authorization")
            ?.split(" ")
            ?.get(1)
        wsFactory.listener.onMessage(wsFactory.ws, TestData.REFRESH_TOKEN.toOkioByteString())
        wsFactory.listener.onFailure(wsFactory.ws, Exception(), null)

        testScheduler.advanceUntilIdle()
        val newToken = wsFactory.request.header("Authorization")
            ?.split(" ")
            ?.get(1)
        Assert.assertNotEquals(oldToken, newToken)
        assertEquals(TestData.REFRESH_TOKEN.refreshToken, newToken)
    }

    @Test
    fun relayConfiguration() = runTest {
        connect(
            with(TestData.JOIN.toBuilder()) {
                join = with(join.toBuilder()) {
                    clientConfiguration = with(LivekitModels.ClientConfiguration.newBuilder()) {
                        forceRelay = LivekitModels.ClientConfigSetting.ENABLED
                        build()
                    }
                    build()
                }
                build()
            },
        )

        val subPeerConnection = getSubscriberPeerConnection()
        assertEquals(PeerConnection.IceTransportsType.RELAY, subPeerConnection.rtcConfig.iceTransportsType)
    }

    @Test
    fun participantIdOnReconnect() = runTest {
        connect()
        wsFactory.listener.onFailure(wsFactory.ws, Exception(), null)

        testScheduler.advanceUntilIdle()
        val sid = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_PARTICIPANT_SID)
        assertEquals(TestData.JOIN.join.participant.sid, sid)
    }
}
