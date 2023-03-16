package io.livekit.android.room

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.livekit.android.assert.assertIsClassList
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.coroutines.TestCoroutineRule
import io.livekit.android.events.*
import io.livekit.android.memory.CloseableManager
import io.livekit.android.mock.*
import io.livekit.android.room.participant.LocalParticipant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowConnectivityManager
import org.webrtc.EglBase

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomTest {

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()

    @get:Rule
    var coroutineRule = TestCoroutineRule()

    lateinit var context: Context

    @Mock
    lateinit var rtcEngine: RTCEngine

    var eglBase: EglBase = MockEglBase()

    val localParticipantFactory = object : LocalParticipant.Factory {
        override fun create(dynacast: Boolean): LocalParticipant {
            return Mockito.mock(LocalParticipant::class.java)
                .apply {
                    whenever(this.events).thenReturn(object : EventListenable<ParticipantEvent> {
                        override val events: SharedFlow<ParticipantEvent> = MutableSharedFlow()
                    })
                }
        }
    }

    lateinit var room: Room

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        room = Room(
            context = context,
            engine = rtcEngine,
            eglBase = eglBase,
            localParticipantFactory = localParticipantFactory,
            defaultsManager = DefaultsManager(),
            defaultDispatcher = coroutineRule.dispatcher,
            ioDispatcher = coroutineRule.dispatcher,
            audioHandler = NoAudioHandler(),
            closeableManager = CloseableManager()
        )
    }

    suspend fun connect() {
        rtcEngine.stub {
            onBlocking { rtcEngine.join(any(), any(), anyOrNull(), anyOrNull()) }
                .doSuspendableAnswer {
                    room.onJoinResponse(SignalClientTest.JOIN.join)
                    SignalClientTest.JOIN.join
                }

        }
        rtcEngine.stub {
            onBlocking { rtcEngine.client }
                .doReturn(Mockito.mock(SignalClient::class.java))
        }

        room.connect(
            url = SignalClientTest.EXAMPLE_URL,
            token = "",
        )
    }

    @Test
    fun connectTest() = runTest {
        connect()
        val roomInfo = SignalClientTest.JOIN.join.room

        assertEquals(roomInfo.name, room.name)
        assertEquals(Room.Sid(roomInfo.sid), room.sid)
        assertEquals(roomInfo.metadata, room.metadata)
        assertEquals(roomInfo.activeRecording, room.isRecording)
    }

    @Test
    fun roomUpdate() = runTest {
        connect()
        val update = SignalClientTest.ROOM_UPDATE.roomUpdate.room

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.onRoomUpdate(update)
        val events = eventCollector.stopCollecting()

        assertEquals(update.metadata, room.metadata)
        assertEquals(update.activeRecording, room.isRecording)

        assertIsClassList(
            listOf(
                RoomEvent.RoomMetadataChanged::class.java,
                RoomEvent.RecordingStatusChanged::class.java,
            ),
            events
        )
    }

    @Test
    fun onConnectionAvailableWillReconnect() = runTest {
        connect()

        val network = Mockito.mock(Network::class.java)

        val connectivityManager = InstrumentationRegistry.getInstrumentation()
            .context
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowConnectivityManager: ShadowConnectivityManager = Shadows.shadowOf(connectivityManager)

        shadowConnectivityManager.networkCallbacks.forEach { callback ->
            callback.onLost(network)
        }
        shadowConnectivityManager.networkCallbacks.forEach { callback ->
            callback.onAvailable(network)
        }

        Mockito.verify(rtcEngine).reconnect()
    }

    @Test
    fun onServerLeave() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.onEngineDisconnected(DisconnectReason.SERVER_SHUTDOWN)
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.Disconnected)
        assertEquals(DisconnectReason.SERVER_SHUTDOWN, (events[0] as RoomEvent.Disconnected).reason)

        // Verify Room state
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertNull(room.sid)
        assertNull(room.metadata)
        assertNull(room.name)
        assertFalse(room.isRecording)
    }

    @Test
    fun onDisconnect() = runTest {

        connect()
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.disconnect()
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.Disconnected)
        assertEquals(DisconnectReason.CLIENT_INITIATED, (events[0] as RoomEvent.Disconnected).reason)

        // Verify Room state
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertNull(room.sid)
        assertNull(room.metadata)
        assertNull(room.name)
        assertFalse(room.isRecording)
    }

    @Test
    fun disconnectCleansUpParticipants() = runTest {
        connect()

        room.onUpdateParticipants(SignalClientTest.PARTICIPANT_JOIN.update.participantsList)
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
        room.onEngineDisconnected(DisconnectReason.CLIENT_INITIATED)
        val events = eventCollector.stopCollecting()

        assertEquals(4, events.size)
        assertEquals(true, events[0] is RoomEvent.TrackUnsubscribed)
        assertEquals(true, events[1] is RoomEvent.TrackUnpublished)
        assertEquals(true, events[2] is RoomEvent.ParticipantDisconnected)
        assertEquals(true, events[3] is RoomEvent.Disconnected)
        Assert.assertTrue(room.remoteParticipants.isEmpty())
    }
}