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
import io.livekit.android.room.ReconnectType
import io.livekit.android.room.Room
import io.livekit.android.room.SignalClient
import io.livekit.android.room.participant.Participant
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.assert.assertIsClass
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.LivekitRtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataTrackManagerMockE2ETest : MockE2ETest() {

    @Test
    fun connectForwardsJoinToInjectedRemoteManager() = runTest {
        connect()

        assertEquals(Room.State.CONNECTED, room.state)
        val remote = remoteDataTrackManagerFactory.manager
        assertTrue(remote.handledJoinResponses.isNotEmpty())
    }

    @Test
    fun publishDataTrackUsesInjectedLocalManager() = runTest {
        connect()

        val result = room.localParticipant.publishDataTrack("telemetry")
        assertTrue(result.isSuccess)
        assertEquals("telemetry", result.getOrThrow().info.name)

        val local = localDataTrackManagerFactory.manager
        assertEquals(1, local.publishedTracks.size)
        assertEquals("telemetry", local.publishedTracks.single().info().name)
    }

    @Test
    fun remoteDataTrackPublishedAttachesToParticipant() = runTest {
        connect()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()

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
    fun softReconnectIncludesPublishedDataTracksInSyncState() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)
        connect()

        val result = room.localParticipant.publishDataTrack("telemetry")
        assertTrue(result.isSuccess)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        advanceUntilIdle()

        val syncState = wsFactory.ws.sentRequests
            .map { LivekitRtc.SignalRequest.parseFrom(it.toPBByteString()) }
            .firstOrNull { it.hasSyncState() }
            ?.syncState
        assertNotNull(syncState)
        assertEquals(1, syncState!!.publishDataTracksCount)
        assertEquals("telemetry", syncState.getPublishDataTracks(0).info.name)
        assertEquals("DT_mock", syncState.getPublishDataTracks(0).info.sid)
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
