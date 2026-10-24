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

import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockAudioStreamTrack
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.MockMediaStream
import io.livekit.android.test.mock.MockRtpReceiver
import io.livekit.android.test.mock.MockVideoStreamTrack
import io.livekit.android.test.mock.SignalRequestHandler
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.createMediaStreamId
import io.livekit.android.test.mock.room.track.createMockLocalAudioTrack
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import livekit.LivekitRtc
import livekit.org.webrtc.PeerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * For tests that only target one reconnection type.
 *
 * Tests that cover all connection types should be put in [RoomReconnectionTypesMockE2ETest].
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomReconnectionMockE2ETest : MockE2ETest() {

    private fun reconnectWebsocket() {
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        val softReconnectParam = wsFactory.request.url
            .queryParameter(SignalClient.CONNECT_QUERY_RECONNECT)
            ?.toIntOrNull()
            ?: 0

        if (softReconnectParam == 0) {
            simulateMessageFromServer(TestData.JOIN)
        } else {
            simulateMessageFromServer(TestData.RECONNECT)
        }
    }

    @Test
    fun softReconnectSendsSyncState() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)

        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        room.onAddTrack(
            MockRtpReceiver.create(),
            MockVideoStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_VIDEO_TRACK.sid,
                    ),
                ),
            ),
        )

        advanceUntilIdle()

        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val sentRequests = wsFactory.ws.sentRequests
        val sentSyncState = sentRequests.any { requestString ->
            val sentRequest = LivekitRtc.SignalRequest.newBuilder()
                .mergeFrom(requestString.toPBByteString())
                .build()

            // Should send zero since we auto subscribe and don't want to unsub from anything.
            assertEquals(0, sentRequest.syncState.subscription.participantTracksCount)
            return@any sentRequest.hasSyncState()
        }

        assertTrue(sentSyncState)
    }

    @Test
    fun softReconnectSendsSyncStatePreSubscribe() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)

        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        advanceUntilIdle()

        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val sentRequests = wsFactory.ws.sentRequests
        val sentSyncState = sentRequests.any { requestString ->
            val sentRequest = LivekitRtc.SignalRequest.newBuilder()
                .mergeFrom(requestString.toPBByteString())
                .build()

            // Should send zero since we auto subscribe and don't want to unsub from anything.
            assertEquals(0, sentRequest.syncState.subscription.participantTracksCount)
            return@any sentRequest.hasSyncState()
        }

        assertTrue(sentSyncState)
    }

    @Test
    fun softReconnectSendsSyncStateUnsub() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)

        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        room.onAddTrack(
            MockRtpReceiver.create(),
            MockVideoStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_VIDEO_TRACK.sid,
                    ),
                ),
            ),
        )

        advanceUntilIdle()

        val remoteTrackPub = room.remoteParticipants.values.first().getTrackPublication(Track.Source.CAMERA) as RemoteTrackPublication
        remoteTrackPub.setSubscribed(false)

        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val sentRequests = wsFactory.ws.sentRequests
        val sentSyncState = sentRequests.any { requestString ->
            val sentRequest = LivekitRtc.SignalRequest.newBuilder()
                .mergeFrom(requestString.toPBByteString())
                .build()

            // Should include the track of the remote participant to unsubscribe.
            assertEquals(1, sentRequest.syncState.subscription.participantTracksCount)
            return@any sentRequest.hasSyncState()
        }

        assertTrue(sentSyncState)
    }

    @Test
    fun softReconnectResendsPackets() = runTest {
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

        for (i in 1..5) {
            assertTrue(room.localParticipant.publishData(ByteArray(i), reliability = DataPublishReliability.RELIABLE).isSuccess)
        }
        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val pubPeerConnection = getPublisherPeerConnection()
        val pubDataChannel = pubPeerConnection.dataChannels[RTCEngine.RELIABLE_DATA_CHANNEL_LABEL] as MockDataChannel

        val expectedResentCount = (1..5).count { it > lastMessageSeq }
        assertEquals(5 + expectedResentCount, pubDataChannel.sentBuffers.size)
    }

    @Test
    fun softReconnectConfiguration() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)
        connect()
        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()

        val rtcConfig = getSubscriberPeerConnection().rtcConfig
        assertEquals(PeerConnection.IceTransportsType.RELAY, rtcConfig.iceTransportsType)

        val sentIceServers = TestData.RECONNECT.reconnect.iceServersList
            .map { server -> server.toWebrtc() }
        assertEquals(sentIceServers, rtcConfig.iceServers)
    }

    @Test
    fun fullReconnectRepublishesTracks() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        // publish track
        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(),
        )

        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val sentRequests = wsFactory.ws.sentRequests
        val sentAddTrack = sentRequests.any { requestString ->
            val sentRequest = LivekitRtc.SignalRequest.newBuilder()
                .mergeFrom(requestString.toPBByteString())
                .build()

            return@any sentRequest.hasAddTrack()
        }

        println(sentRequests)
        assertTrue(sentAddTrack)
    }

    @Test
    fun softReconnectKeepsFeatureUpdatesFromPublication() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)
        connect()

        val audioTrack = createMockLocalAudioTrack()
        room.localParticipant.publishAudioTrack(track = audioTrack)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        advanceUntilIdle()

        val baseline = countAudioTrackFeatureUpdates()
        assertTrue(audioTrack.applyOptions(audioTrack.options.copy(echoCancellation = false)).isSuccess)
        advanceUntilIdle()

        assertEquals(1, countAudioTrackFeatureUpdates() - baseline)
    }

    @Test
    fun fullReconnectStopsFeatureUpdatesFromOldPublication() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        val audioTrack = createMockLocalAudioTrack()
        room.localParticipant.publishAudioTrack(track = audioTrack)

        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        testScheduler.advanceUntilIdle()

        val baseline = countAudioTrackFeatureUpdates()

        // A features change should be reported once, by the republished publication.
        // A second update means the old publication's feature collector is still
        // running and reporting a stale track sid.
        assertTrue(audioTrack.applyOptions(audioTrack.options.copy(echoCancellation = false)).isSuccess)
        testScheduler.advanceUntilIdle()

        assertEquals(1, countAudioTrackFeatureUpdates() - baseline)
    }

    @Test
    fun fullReconnectStopsFeatureUpdatesFromPublishCompletingDuringPreparation() = runTest {
        connect()

        var deferredAddTrack: LivekitRtc.AddTrackRequest? = null
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasAddTrack() && deferredAddTrack == null) {
                deferredAddTrack = request.addTrack
                true
            } else {
                false
            }
        }

        val audioTrack = createMockLocalAudioTrack()
        val publish = async(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.publishAudioTrack(audioTrack)
        }
        runCurrent()

        val addTrack = requireNotNull(deferredAddTrack)
        wsFactory.receiveMessage(
            LivekitRtc.SignalResponse.newBuilder()
                .setTrackPublished(
                    LivekitRtc.TrackPublishedResponse.newBuilder()
                        .setCid(addTrack.cid)
                        .setTrack(TestData.LOCAL_AUDIO_TRACK),
                )
                .build(),
        )
        // The old session accepted the response, but the publish continuation has
        // not created its publication or feature collector yet.
        room.localParticipant.prepareForFullReconnect()
        runCurrent()
        assertTrue(publish.await())

        val baseline = countAudioTrackFeatureUpdates()
        assertTrue(audioTrack.applyOptions(audioTrack.options.copy(echoCancellation = false)).isSuccess)
        runCurrent()

        assertEquals(0, countAudioTrackFeatureUpdates() - baseline)
    }

    @Test
    fun fullReconnectStopsFeatureUpdatesFromPublishAcceptedByOldSessionAfterPreparation() = runTest {
        connect()

        var deferredAddTrack: LivekitRtc.AddTrackRequest? = null
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasAddTrack() && deferredAddTrack == null) {
                deferredAddTrack = request.addTrack
                true
            } else {
                false
            }
        }

        val audioTrack = createMockLocalAudioTrack()
        val publish = async(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.publishAudioTrack(audioTrack)
        }
        runCurrent()

        room.localParticipant.prepareForFullReconnect()

        // The old signal session can still deliver responses until its replacement starts.
        val addTrack = requireNotNull(deferredAddTrack)
        wsFactory.receiveMessage(
            LivekitRtc.SignalResponse.newBuilder()
                .setTrackPublished(
                    LivekitRtc.TrackPublishedResponse.newBuilder()
                        .setCid(addTrack.cid)
                        .setTrack(TestData.LOCAL_AUDIO_TRACK),
                )
                .build(),
        )
        advanceUntilIdle()
        assertTrue(publish.getCompleted())

        val baseline = countAudioTrackFeatureUpdates()
        assertTrue(audioTrack.applyOptions(audioTrack.options.copy(echoCancellation = false)).isSuccess)
        advanceUntilIdle()

        assertEquals(0, countAudioTrackFeatureUpdates() - baseline)
    }

    @Test
    fun fullReconnectKeepsFeatureUpdatesFromPublishAcceptedByNewSession() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        val oldWebSocket = wsFactory.ws
        val idRequested = CountDownLatch(1)
        val resumeId = CountDownLatch(1)
        val publishCompleted = CountDownLatch(1)
        val mediaTrack = Mockito.spy(
            MockAudioStreamTrack(id = TestData.LOCAL_TRACK_PUBLISHED.trackPublished.cid),
        )
        Mockito.doAnswer {
            idRequested.countDown()
            check(resumeId.await(5, TimeUnit.SECONDS)) { "Timed out waiting for full reconnect" }
            TestData.LOCAL_TRACK_PUBLISHED.trackPublished.cid
        }.`when`(mediaTrack).id()

        val audioTrack = createMockLocalAudioTrack(mediaTrack = mediaTrack)
        val publish = async(Dispatchers.Default) {
            room.localParticipant.publishAudioTrack(audioTrack)
        }
        // Completion handlers run after the Deferred reaches its final state, so the
        // latch guarantees getCompleted() below cannot race the state transition. The
        // test body must not suspend on publish.await(): it would resume inside the
        // publish coroutine's frame, where the unconfined event loop defers the
        // applyOptions feature propagation past the assertions.
        publish.invokeOnCompletion { publishCompleted.countDown() }

        try {
            // The publish has passed its connection-state check but has not called addTrack.
            assertTrue(idRequested.await(5, TimeUnit.SECONDS))

            disconnectPeerConnection()
            testScheduler.advanceTimeBy(1000)
            assertNotSame(oldWebSocket, wsFactory.ws)
            reconnectWebsocket()
            connectPeerConnection()

            // addTrack is sent through the replacement signal session and accepted there.
            resumeId.countDown()
            assertTrue(publishCompleted.await(5, TimeUnit.SECONDS))
            assertTrue(publish.getCompleted())

            val baseline = countAudioTrackFeatureUpdates()
            assertTrue(audioTrack.applyOptions(audioTrack.options.copy(echoCancellation = false)).isSuccess)
            advanceUntilIdle()

            assertEquals(1, countAudioTrackFeatureUpdates() - baseline)
        } finally {
            resumeId.countDown()
        }
    }

    private fun countAudioTrackFeatureUpdates() = wsFactory.ws.sentRequests.count { requestString ->
        LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString.toPBByteString())
            .build()
            .hasUpdateAudioTrack()
    }
}
