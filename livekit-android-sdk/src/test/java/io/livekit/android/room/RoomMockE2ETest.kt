package io.livekit.android.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.coroutines.TestCoroutineRule
import io.livekit.android.events.EventCollector
import io.livekit.android.events.RoomEvent
import io.livekit.android.mock.*
import io.livekit.android.mock.dagger.DaggerTestLiveKitComponent
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.track.Track
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runBlockingTest
import livekit.LivekitRtc
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomMockE2ETest {

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()

    @get:Rule
    var coroutineRule = TestCoroutineRule()

    lateinit var context: Context
    lateinit var room: Room
    lateinit var wsFactory: MockWebsocketFactory

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val component = DaggerTestLiveKitComponent
            .factory()
            .create(context)

        room = component.roomFactory()
            .create(context)
        wsFactory = component.websocketFactory()
    }

    fun connect() {
        val job = coroutineRule.scope.launch {
            room.connect(
                url = "http://www.example.com",
                token = "",
            )
        }

        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.JOIN.toOkioByteString())

        runBlockingTest {
            job.join()
        }
    }

    @Test
    fun connectTest() {
        connect()
    }

    @Test
    fun connectFailureProperlyContinues(){

        var didThrowException = false
        val job = coroutineRule.scope.launch {
            try {
                room.connect(
                    url = "http://www.example.com",
                    token = "",
                )
            } catch (e: Throwable) {
                didThrowException = true
            }
        }

        wsFactory.listener.onFailure(wsFactory.ws, Exception(), null)

        runBlockingTest {
            job.join()
        }

        Assert.assertTrue(didThrowException)
    }
    @Test
    fun roomUpdateTest() {
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
    fun connectionQualityUpdateTest() {
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
    fun participantConnected() {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.PARTICIPANT_JOIN.toOkioByteString()
        )
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.ParticipantConnected)
    }

    @Test
    fun participantDisconnected() {
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

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.ParticipantDisconnected)
    }

    @Test
    fun onActiveSpeakersChanged() {
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
    fun participantMetadataChanged() {
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
    fun trackStreamStateChanged() {
        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.PARTICIPANT_JOIN.toOkioByteString()
        )

        // We intentionally don't emit if the track isn't subscribed, so need to
        // add track.
        room.onAddTrack(
            MockAudioStreamTrack(),
            arrayOf(MockMediaStream(id = "${TestData.REMOTE_PARTICIPANT.sid}|${TestData.REMOTE_AUDIO_TRACK.sid}"))
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
    fun leave() {
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

}