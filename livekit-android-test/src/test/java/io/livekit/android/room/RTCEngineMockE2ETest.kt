/*
 * Copyright 2023-2026 LiveKit, Inc.
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

import com.google.protobuf.ByteString
import io.livekit.android.room.track.TrackException
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.events.FlowCollector
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.SignalRequestHandler
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.TimeoutException
import io.livekit.android.util.flow
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import livekit.LivekitModels
import livekit.LivekitModels.DataPacket
import livekit.LivekitRtc
import livekit.org.webrtc.PeerConnection
import org.junit.Assert
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun roomConnectDoesNotHangOnWebSocketFailure() = runTest {
        val connectJob = async {
            try {
                room.connect(
                    url = TestData.EXAMPLE_URL,
                    token = "token",
                )
                null
            } catch (e: Throwable) {
                e
            }
        }

        room::state.flow
            .takeWhile { it != Room.State.CONNECTING }
            .collect()
        runCurrent()

        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        // simulate websocket failure
        wsFactory.ws.cancel()
        advanceUntilIdle()

        val connectResult = connectJob.await()
        assertTrue("connect job should fail on websocket cancel", connectResult != null)
        assertTrue(
            "Expected RoomException.ConnectException, got $connectResult",
            connectResult is RoomException.ConnectException,
        )
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

        testScheduler.advanceTimeBy(1000)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.RECONNECT)

        val sid = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_PARTICIPANT_SID)
        assertEquals(TestData.JOIN.join.participant.sid, sid)
    }

    @Test
    fun publishDataRejectsLargePacket() = runTest {
        connect()
        val pubDataChannel = getPublisherPeerConnection()
            .dataChannels[RTCEngine.RELIABLE_DATA_CHANNEL_LABEL] as MockDataChannel

        val oversizedPayload = ByteArray(65 * 1024) // See RTCEngine.MAX_DATA_PACKET_SIZE
        val result = room.localParticipant.publishData(oversizedPayload)

        assertTrue(result.isFailure)
        assertTrue(
            "Expected IllegalArgumentException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals(0, pubDataChannel.sentBuffers.size)
    }

    @Test
    fun resendReliableMessagesReplaysFullPayloadAcrossMultipleResumes() = runTest {
        connect()
        val pubPeerConnection = getPublisherPeerConnection()
        val pubDataChannel = pubPeerConnection
            .dataChannels[RTCEngine.RELIABLE_DATA_CHANNEL_LABEL] as MockDataChannel
        pubDataChannel.consumeSentBuffer = true

        val payload = "hello-resume".toByteArray()
        val publishResult = room.localParticipant.publishData(payload)
        assertTrue(publishResult.isSuccess)

        // Two consecutive resumes without server progress (lastMessageSeq=0 pops nothing).
        rtcEngine.resendReliableMessagesForResume(0)
        rtcEngine.resendReliableMessagesForResume(0)

        // 1 initial publish + 2 replays.
        assertEquals(3, pubDataChannel.sentPayloads.size)

        // Both replays must carry the original payload, not an empty buffer.
        val replay1 = DataPacket.parseFrom(pubDataChannel.sentPayloads[1])
        val replay2 = DataPacket.parseFrom(pubDataChannel.sentPayloads[2])
        assertArrayEquals(payload, replay1.user.payload.toByteArray())
        assertArrayEquals(payload, replay2.user.payload.toByteArray())
    }

    @Test
    fun resendReliableMessagesReturnsFailureWhenReplaySendFails() = runTest {
        connect()
        val pubPeerConnection = getPublisherPeerConnection()
        val pubDataChannel = pubPeerConnection
            .dataChannels[RTCEngine.RELIABLE_DATA_CHANNEL_LABEL] as MockDataChannel

        val publishResult = room.localParticipant.publishData("queued".toByteArray())
        assertTrue(publishResult.isSuccess)

        pubDataChannel.sendResult = false

        val resendResult = rtcEngine.resendReliableMessagesForResume(0)
        assertTrue(resendResult.isFailure)
        assertTrue(resendResult.exceptionOrNull() is RoomException.ConnectException)
    }

    /**
     * Regression: an AddTrack timeout used to leave the cid in pendingTrackResolvers,
     * poisoning every subsequent publish of the same track with DuplicateTrackException
     * until the connection was torn down (or the server eventually responded.
     */
    @Test
    fun addTrackTimeoutDoesNotPoisonRetry() = runTest {
        connect()
        // The default mock handler auto-replies to ADD_TRACK; remove it so we can
        // simulate the "server never responds" case that triggers the timeout.
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)

        // Use a SupervisorJob so timeout/cancellation in addTrack does not cancel the test scope.
        val supervisor = CoroutineScope(coroutineRule.dispatcher + SupervisorJob())
        try {
            val cid = TestData.LOCAL_TRACK_PUBLISHED.trackPublished.cid

            val firstPublish = supervisor.async {
                rtcEngine.addTrack(cid, "audio", LivekitModels.TrackType.AUDIO, stream = null)
            }
            runCurrent()

            // Push past the 20s AddTrack deadline without the server replying.
            testScheduler.advanceTimeBy(21_000)
            runCurrent()

            assertTrue("firstPublish should be completed", firstPublish.isCompleted)
            val firstFailure = firstPublish.getCompletionExceptionOrNull()
            assertTrue(
                "Expected TimeoutException, got $firstFailure",
                firstFailure is TimeoutException,
            )

            // Retry with the same cid — must not be rejected by the duplicate guard.
            val secondPublish = supervisor.async {
                rtcEngine.addTrack(cid, "audio", LivekitModels.TrackType.AUDIO, stream = null)
            }
            // runCurrent (not advanceUntilIdle): otherwise the new 20s deadline fires
            // before the simulated server response is delivered.
            runCurrent()

            if (secondPublish.isCompleted) {
                val syncFailure = secondPublish.getCompletionExceptionOrNull()
                assertFalse(
                    "Retry must not fail synchronously with DuplicateTrackException, got $syncFailure",
                    syncFailure is TrackException.DuplicateTrackException,
                )
            }

            // Server now replies for the retry; the second publish should resolve cleanly.
            simulateMessageFromServer(TestData.LOCAL_TRACK_PUBLISHED)
            runCurrent()

            assertTrue("secondPublish should be completed", secondPublish.isCompleted)
            val secondFailure = secondPublish.getCompletionExceptionOrNull()
            assertTrue("Retry should have succeeded, got $secondFailure", secondFailure == null)
            assertEquals(
                TestData.LOCAL_TRACK_PUBLISHED.trackPublished.track.sid,
                secondPublish.getCompleted().sid,
            )
        } finally {
            supervisor.cancel()
        }
    }

    /**
     * Regression: caller cancellation of an in-flight addTrack must also clean up
     * the pendingTrackResolvers entry so the same cid can be retried.
     */
    @Test
    fun addTrackCallerCancellationDoesNotPoisonRetry() = runTest {
        connect()
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)

        val supervisor = CoroutineScope(coroutineRule.dispatcher + SupervisorJob())
        try {
            val cid = TestData.LOCAL_TRACK_PUBLISHED.trackPublished.cid

            val firstPublish = supervisor.async {
                rtcEngine.addTrack(cid, "audio", LivekitModels.TrackType.AUDIO, stream = null)
            }
            runCurrent()
            assertFalse("firstPublish should still be in-flight", firstPublish.isCompleted)

            firstPublish.cancel()
            runCurrent()
            assertTrue("firstPublish should be cancelled", firstPublish.isCancelled)

            val secondPublish = supervisor.async {
                rtcEngine.addTrack(cid, "audio", LivekitModels.TrackType.AUDIO, stream = null)
            }
            runCurrent()

            if (secondPublish.isCompleted) {
                val syncFailure = secondPublish.getCompletionExceptionOrNull()
                assertFalse(
                    "Retry must not fail synchronously with DuplicateTrackException, got $syncFailure",
                    syncFailure is TrackException.DuplicateTrackException,
                )
            }

            simulateMessageFromServer(TestData.LOCAL_TRACK_PUBLISHED)
            runCurrent()

            assertTrue("secondPublish should be completed", secondPublish.isCompleted)
            val secondFailure = secondPublish.getCompletionExceptionOrNull()
            assertTrue(
                "Retry after cancellation should have succeeded, got $secondFailure",
                secondFailure == null,
            )
        } finally {
            supervisor.cancel()
        }
    }

    /**
     * After a soft reconnect, the server reports [LivekitRtc.ReconnectResponse.lastMessageSeq]. The engine
     * drops buffered reliable payloads up to that sequence (inclusive) and re-sends the remainder on the
     * reliable data channel — see [RTCEngine.resendReliableMessagesForResume].
     */
    @Test
    fun softReconnectResendsBufferedReliableData() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)

        val publisherOfferHandler: SignalRequestHandler = { request ->
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
            } else {
                false
            }
        }
        wsFactory.registerSignalRequestHandler(publisherOfferHandler)
        connect()

        val lastMessageSeq = TestData.RECONNECT.reconnect.lastMessageSeq
        val pubDataChannel =
            getPublisherPeerConnection().dataChannels[RTCEngine.RELIABLE_DATA_CHANNEL_LABEL] as MockDataChannel

        val payloads = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        for (payload in payloads) {
            assertTrue(room.localParticipant.publishData(payload).isSuccess)
        }
        assertEquals(3, pubDataChannel.sentBuffers.size)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.RECONNECT)
        connectPeerConnection()

        advanceUntilIdle()

        val expectedResentSequences = listOf(1, 2, 3).filter { it > lastMessageSeq }
        assertEquals(3 + expectedResentSequences.size, pubDataChannel.sentBuffers.size)
        expectedResentSequences.forEachIndexed { index, sequence ->
            val packet = DataPacket.parseFrom(
                ByteString.copyFrom(pubDataChannel.sentBuffers[3 + index].data),
            )
            assertEquals(sequence, packet.sequence)
            assertTrue(
                packet.user.payload.toByteArray().contentEquals(byteArrayOf(sequence.toByte())),
            )
        }
    }
}
