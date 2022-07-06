package io.livekit.android.room

import android.content.Context
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.coroutines.TestCoroutineRule
import io.livekit.android.events.EventCollector
import io.livekit.android.events.EventListenable
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.mock.*
import io.livekit.android.room.participant.LocalParticipant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
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
    }

    @Test
    fun onConnectionAvailableWillReconnect() = runTest {
        connect()

        val network = Mockito.mock(Network::class.java)
        room.onLost(network)
        room.onAvailable(network)
        Mockito.verify(rtcEngine).reconnect()
    }

    @Test
    fun onDisconnect() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.onEngineDisconnected("")
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.Disconnected)
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
        room.onEngineDisconnected("")
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(4, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.TrackUnsubscribed)
        Assert.assertEquals(true, events[1] is RoomEvent.TrackUnpublished)
        Assert.assertEquals(true, events[2] is RoomEvent.ParticipantDisconnected)
        Assert.assertEquals(true, events[3] is RoomEvent.Disconnected)
        Assert.assertTrue(room.remoteParticipants.isEmpty())
    }
}