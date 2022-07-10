package io.livekit.android.room

import android.net.Network
import io.livekit.android.MockE2ETest
import io.livekit.android.events.EventCollector
import io.livekit.android.events.FlowCollector
import io.livekit.android.events.RoomEvent
import io.livekit.android.mock.MockAudioStreamTrack
import io.livekit.android.mock.MockMediaStream
import io.livekit.android.mock.TestData
import io.livekit.android.mock.createMediaStreamId
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import io.livekit.android.util.toOkioByteString
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomMockE2ETest : MockE2ETest() {

    @Test
    fun connectTest() = runTest {
        val collector = FlowCollector(room::state.flow, coroutineRule.scope)
        connect()
        val events = collector.stopCollecting()
        Assert.assertEquals(3, events.size)
        Assert.assertEquals(Room.State.DISCONNECTED, events[0])
        Assert.assertEquals(Room.State.CONNECTING, events[1])
        Assert.assertEquals(Room.State.CONNECTED, events[2])
    }

    @Test
    fun connectNoEvents() = runTest {
        val collector = EventCollector(room.events, coroutineRule.scope)
        connect()
        val events = collector.stopCollecting()
        assertEquals(emptyList<RoomEvent>(), events)
    }

    @Test
    fun connectNoEventsWithRemoteParticipant() = runTest {
        val joinResponse = with(SignalClientTest.JOIN.toBuilder()) {
            join = with(SignalClientTest.JOIN.join.toBuilder()) {
                addOtherParticipants(TestData.REMOTE_PARTICIPANT)
                build()
            }
            build()
        }

        val collector = EventCollector(room.events, coroutineRule.scope)
        connect(joinResponse)
        val events = collector.stopCollecting()
        assertEquals(emptyList<RoomEvent>(), events)
    }

    @Test
    fun connectFailureProperlyContinues() = runTest {

        var didThrowException = false
        val job = coroutineRule.scope.launch {
            try {
                room.connect(
                    url = SignalClientTest.EXAMPLE_URL,
                    token = "",
                )
            } catch (e: Throwable) {
                didThrowException = true
            }
        }

        wsFactory.listener.onFailure(wsFactory.ws, Exception(), null)

        job.join()

        Assert.assertTrue(didThrowException)
    }

    @Test
    fun roomUpdateTest() = runTest {
        connect()
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.ROOM_UPDATE.toOkioByteString())
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(
            SignalClientTest.ROOM_UPDATE.roomUpdate.room.metadata,
            room.metadata
        )
        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.RoomMetadataChanged)
    }

    @Test
    fun connectionQualityUpdateTest() = runTest {
        connect()
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.CONNECTION_QUALITY.toOkioByteString()
        )
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(ConnectionQuality.EXCELLENT, room.localParticipant.connectionQuality)
        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.ConnectionQualityChanged)
    }

    @Test
    fun participantConnected() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        simulateMessageFromServer(SignalClientTest.PARTICIPANT_JOIN)
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(2, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.ParticipantConnected)
        Assert.assertEquals(true, events[1] is RoomEvent.TrackPublished)
    }

    @Test
    fun participantDisconnected() = runTest {
        connect()
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.PARTICIPANT_JOIN.toOkioByteString()
        )

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.PARTICIPANT_DISCONNECT.toOkioByteString()
        )
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(2, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.TrackUnpublished)
        Assert.assertEquals(true, events[1] is RoomEvent.ParticipantDisconnected)
    }

    @Test
    fun onActiveSpeakersChanged() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.ACTIVE_SPEAKER_UPDATE.toOkioByteString()
        )
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.ActiveSpeakersChanged)
    }

    @Test
    fun participantMetadataChanged() = runTest {
        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.PARTICIPANT_JOIN.toOkioByteString()
        )

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.PARTICIPANT_METADATA_CHANGED.toOkioByteString()
        )
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.ParticipantMetadataChanged)
    }

    @Test
    fun trackStreamStateChanged() = runTest {
        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.PARTICIPANT_JOIN.toOkioByteString()
        )

        // We intentionally don't emit if the track isn't subscribed, so need to
        // add track.
        room.onAddTrack(
            MockAudioStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_AUDIO_TRACK.sid
                    )
                )
            )
        )
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.STREAM_STATE_UPDATE.toOkioByteString()
        )
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.TrackStreamStateChanged)

        val event = events[0] as RoomEvent.TrackStreamStateChanged
        Assert.assertEquals(Track.StreamState.ACTIVE, event.streamState)
    }

    @Test
    fun trackSubscriptionPermissionChanged() = runTest {
        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.PARTICIPANT_JOIN.toOkioByteString()
        )
        room.onAddTrack(
            MockAudioStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_AUDIO_TRACK.sid
                    )
                )
            )
        )
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.SUBSCRIPTION_PERMISSION_UPDATE.toOkioByteString()
        )
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.TrackSubscriptionPermissionChanged)

        val event = events[0] as RoomEvent.TrackSubscriptionPermissionChanged
        Assert.assertEquals(TestData.REMOTE_PARTICIPANT.sid, event.participant.sid)
        Assert.assertEquals(TestData.REMOTE_AUDIO_TRACK.sid, event.trackPublication.sid)
        Assert.assertEquals(false, event.subscriptionAllowed)
    }

    @Test
    fun onConnectionAvailableWillReconnect() = runTest {
        connect()
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        val network = Mockito.mock(Network::class.java)
        room.onLost(network)
        room.onAvailable(network)
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.Reconnecting)
    }

    @Test
    fun leave() = runTest {
        connect()
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.LEAVE.toOkioByteString()
        )
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.Disconnected)
    }

    @Test
    fun disconnectCleansLocalParticipant() = runTest {
        connect()

        val publishJob = launch {
            room.localParticipant.publishAudioTrack(
                LocalAudioTrack(
                    "",
                    MockAudioStreamTrack(id = SignalClientTest.LOCAL_TRACK_PUBLISHED.trackPublished.cid)
                )
            )
        }
        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.LOCAL_TRACK_PUBLISHED.toOkioByteString())
        publishJob.join()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.disconnect()
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(2, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.TrackUnpublished)
        Assert.assertEquals(true, events[1] is RoomEvent.Disconnected)
    }

    @Test
    fun connectAfterDisconnect() = runTest {
        connect()
        room.disconnect()
        connect()
        Assert.assertEquals(room.state, Room.State.CONNECTED)
    }

    @Test
    fun reconnectFromPeerConnectionDisconnect() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.onOpen = {
            wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
            connectPeerConnection()
        }
        disconnectPeerConnection()
        val events = eventCollector.stopCollecting()

        assertEquals(2, events.size)
        assertTrue(events[0] is RoomEvent.Reconnecting)
        assertTrue(events[1] is RoomEvent.Reconnected)
    }
}