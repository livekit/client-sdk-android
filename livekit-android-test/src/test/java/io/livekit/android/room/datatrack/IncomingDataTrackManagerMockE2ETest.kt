/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room.datatrack

import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.ReconnectType
import io.livekit.android.room.Room
import io.livekit.android.room.SignalClient
import io.livekit.android.room.participant.Participant
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.assert.assertIsClass
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.SignalRequestHandler
import io.livekit.android.test.mock.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.LivekitRtc
import livekit.org.webrtc.DataChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingDataTrackManagerMockE2ETest : MockE2ETest() {

    @Test
    fun subscriberDataTrackChannelForwardsPacketsToIncomingManager() = runTest {
        connect()
        val channel = openSubscriberDataTrackChannel()
        val payload = byteArrayOf(9, 8, 7)
        receiveDataTrackPacket(channel, payload)

        val packets = remoteDataTrackManagerFactory.manager.handledPackets
        assertEquals(1, packets.size)
        assertArrayEquals(payload, packets.single())
    }

    @Test
    fun fullReconnectForwardsPacketsOnReplacementSubscriberDataTrackChannel() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        wsFactory.registerSignalRequestHandler(publisherOfferHandler)
        connect()
        val original = openSubscriberDataTrackChannel()
        receiveDataTrackPacket(original, byteArrayOf(1))

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        advanceUntilIdle()

        val remote = remoteDataTrackManagerFactory.manager
        assertFalse(remote.closed)

        val replacement = openSubscriberDataTrackChannel()
        assertNotSame(original, replacement)
        receiveDataTrackPacket(replacement, byteArrayOf(2))

        assertEquals(2, remote.handledPackets.size)
        assertArrayEquals(byteArrayOf(1), remote.handledPackets[0])
        assertArrayEquals(byteArrayOf(2), remote.handledPackets[1])
    }

    @Test
    fun connectForwardsJoinToInjectedRemoteManager() = runTest {
        connect()

        assertEquals(Room.State.CONNECTED, room.state)
        val remote = remoteDataTrackManagerFactory.manager
        assertTrue(remote.handledJoinResponses.isNotEmpty())
        assertArrayEquals(TestData.JOIN.toByteArray(), remote.handledJoinResponses.first())
    }

    @Test
    fun incomingDataTrackAlwaysReceivesDecryptionProvider() = runTest {
        connect()
        assertNotNull(remoteDataTrackManagerFactory.lastDecryptionProvider)
    }

    @Test
    fun remoteDataTrackPublishedAttachesToParticipant() = runTest {
        connect()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()
        assertArrayEquals(
            TestData.PARTICIPANT_JOIN.toByteArray(),
            remoteDataTrackManagerFactory.manager.handledParticipantUpdates.last(),
        )

        val participant = remoteParticipant()
        val roomCollector = EventCollector(room.events, coroutineRule.scope)
        val participantCollector = EventCollector(participant.events, coroutineRule.scope)

        remoteDataTrackManagerFactory.manager.simulateTrackPublished(
            name = "telemetry",
            publisherIdentity = TestData.REMOTE_PARTICIPANT.identity,
            sid = "DT_test",
        )
        advanceUntilIdle()

        val attached = participant.dataTracks["telemetry"]
        assertNotNull(attached)
        assertEquals("telemetry", attached!!.name)
        assertEquals(DataTrackSid("DT_test"), attached.info.sid)

        val roomEvents = roomCollector.stopCollecting()
        val participantEvents = participantCollector.stopCollecting()

        assertEquals(1, roomEvents.size)
        assertIsClass(RoomEvent.DataTrackPublished::class.java, roomEvents.first())
        val roomEvent = roomEvents.first() as RoomEvent.DataTrackPublished
        assertEquals(participant, roomEvent.participant)
        assertEquals(attached, roomEvent.track)

        assertEquals(1, participantEvents.size)
        assertIsClass(ParticipantEvent.DataTrackPublished::class.java, participantEvents.first())
    }

    @Test
    fun remoteDataTrackPublishedBeforeParticipantIsParkedThenAttached() = runTest {
        connect()

        val parkedCollector = EventCollector(room.events, coroutineRule.scope)
        remoteDataTrackManagerFactory.manager.simulateTrackPublished(
            name = "parked",
            publisherIdentity = TestData.REMOTE_PARTICIPANT.identity,
            sid = "DT_parked",
        )
        advanceUntilIdle()

        assertTrue(room.remoteParticipants.isEmpty())
        assertTrue(parkedCollector.stopCollecting().none { it is RoomEvent.DataTrackPublished })

        val attachedCollector = EventCollector(room.events, coroutineRule.scope)
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()

        val participant = remoteParticipant()
        val attached = participant.dataTracks["parked"]
        assertNotNull(attached)
        assertEquals("parked", attached!!.name)

        val events = attachedCollector.stopCollecting()
        assertTrue(events.any { it is RoomEvent.DataTrackPublished })
    }

    @Test
    fun remoteDataTrackUnpublishedRemovesFromParticipant() = runTest {
        connect()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()

        val participant = remoteParticipant()
        remoteDataTrackManagerFactory.manager.simulateTrackPublished(
            name = "telemetry",
            publisherIdentity = TestData.REMOTE_PARTICIPANT.identity,
            sid = "DT_unpub",
        )
        advanceUntilIdle()
        assertNotNull(participant.dataTracks["telemetry"])

        val roomCollector = EventCollector(room.events, coroutineRule.scope)
        val participantCollector = EventCollector(participant.events, coroutineRule.scope)

        remoteDataTrackManagerFactory.manager.simulateTrackUnpublished("DT_unpub")
        advanceUntilIdle()

        assertNull(participant.dataTracks["telemetry"])

        val roomEvents = roomCollector.stopCollecting()
        val participantEvents = participantCollector.stopCollecting()

        assertEquals(1, roomEvents.size)
        assertIsClass(RoomEvent.DataTrackUnpublished::class.java, roomEvents.first())
        assertEquals(DataTrackSid("DT_unpub"), (roomEvents.first() as RoomEvent.DataTrackUnpublished).sid)

        assertEquals(1, participantEvents.size)
        assertIsClass(ParticipantEvent.DataTrackUnpublished::class.java, participantEvents.first())
    }

    @Test
    fun participantDisconnectUnpublishesDataTracks() = runTest {
        connect()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()

        remoteDataTrackManagerFactory.manager.simulateTrackPublished(
            name = "telemetry",
            publisherIdentity = TestData.REMOTE_PARTICIPANT.identity,
            sid = "DT_leave",
        )
        advanceUntilIdle()

        val roomCollector = EventCollector(room.events, coroutineRule.scope)
        simulateMessageFromServer(TestData.PARTICIPANT_DISCONNECT)
        advanceUntilIdle()

        val events = roomCollector.stopCollecting()
        assertTrue(events.any { it is RoomEvent.DataTrackUnpublished && it.sid == DataTrackSid("DT_leave") })
        assertTrue(events.any { it is RoomEvent.ParticipantDisconnected })
        assertTrue(room.remoteParticipants.isEmpty())
    }

    @Test
    fun fullReconnectDetachesDataTracksWithoutUnpublishEvent() = runTest {
        connect()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()

        remoteDataTrackManagerFactory.manager.simulateTrackPublished(
            name = "telemetry",
            publisherIdentity = TestData.REMOTE_PARTICIPANT.identity,
            sid = "DT_reconnect",
        )
        advanceUntilIdle()

        val roomCollector = EventCollector(room.events, coroutineRule.scope)
        room.onFullReconnecting()
        advanceUntilIdle()

        val events = roomCollector.stopCollecting()
        assertTrue(events.none { it is RoomEvent.DataTrackUnpublished })
    }

    @Test
    fun softReconnectResendsDataTrackSubscriptions() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)
        connect()

        val remote = remoteDataTrackManagerFactory.manager
        assertEquals(0, remote.resendSubscriptionUpdatesCount)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        advanceUntilIdle()

        assertEquals(1, remote.resendSubscriptionUpdatesCount)
    }

    @Test
    fun fullReconnectResendsSubscriptionsAfterSubscriberDataTrackOpens() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        wsFactory.registerSignalRequestHandler(publisherOfferHandler)
        connect()
        remoteDataTrackManagerFactory.manager.simulateTrackPublished(
            name = "telemetry",
            publisherIdentity = TestData.REMOTE_PARTICIPANT.identity,
        )

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        testScheduler.runCurrent()

        val remote = remoteDataTrackManagerFactory.manager
        assertEquals(0, remote.resendSubscriptionUpdatesCount)
        assertEquals(Room.State.RECONNECTING, room.state)

        val channel = MockDataChannel(RTCEngine.DATA_TRACK_DATA_CHANNEL_LABEL)
        channel.state = DataChannel.State.CONNECTING
        getSubscriberPeerConnection().observer?.onDataChannel(channel)
        testScheduler.runCurrent()
        assertEquals(0, remote.resendSubscriptionUpdatesCount)
        assertEquals(Room.State.RECONNECTING, room.state)

        channel.state = DataChannel.State.OPEN
        advanceUntilIdle()
        assertEquals(1, remote.resendSubscriptionUpdatesCount)
        assertEquals(Room.State.CONNECTED, room.state)
    }

    private val publisherOfferHandler: SignalRequestHandler = { request ->
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

    private fun openSubscriberDataTrackChannel(): MockDataChannel {
        val channel = MockDataChannel(RTCEngine.DATA_TRACK_DATA_CHANNEL_LABEL)
        getSubscriberPeerConnection().observer?.onDataChannel(channel)
        return channel
    }

    private fun receiveDataTrackPacket(channel: MockDataChannel, payload: ByteArray) {
        channel.simulateBufferReceived(
            DataChannel.Buffer(ByteBuffer.wrap(payload), true),
        )
    }

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

    private fun remoteParticipant() =
        room.remoteParticipants[Participant.Identity(TestData.REMOTE_PARTICIPANT.identity)]!!
}
