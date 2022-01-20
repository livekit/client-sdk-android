package io.livekit.android.room

import android.content.Context
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.coroutines.TestCoroutineRule
import io.livekit.android.events.EventCollector
import io.livekit.android.events.RoomEvent
import io.livekit.android.mock.MockEglBase
import io.livekit.android.room.participant.LocalParticipant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runBlockingTest
import livekit.LivekitModels
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.stub
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

    val localParticantFactory = object : LocalParticipant.Factory {
        override fun create(info: LivekitModels.ParticipantInfo, dynacast: Boolean): LocalParticipant {
            return Mockito.mock(LocalParticipant::class.java)
        }
    }

    lateinit var room: Room

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        room = Room(
            context,
            rtcEngine,
            eglBase,
            localParticantFactory,
            DefaultsManager(),
            coroutineRule.dispatcher,
            coroutineRule.dispatcher,
        )
    }

    fun connect() {
        rtcEngine.stub {
            onBlocking { rtcEngine.join(any(), any(), anyOrNull()) }
                .doReturn(SignalClientTest.JOIN.join)
        }
        rtcEngine.stub {
            onBlocking { rtcEngine.client }
                .doReturn(Mockito.mock(SignalClient::class.java))
        }
        val job = coroutineRule.scope.launch {
            room.connect(
                url = SignalClientTest.EXAMPLE_URL,
                token = "",
            )
        }
        runBlockingTest {
            job.join()
        }
    }

    @Test
    fun connectTest() {
        connect()
    }

    @Test
    fun onConnectionAvailableWillReconnect() {
        connect()

        val network = Mockito.mock(Network::class.java)
        room.onLost(network)
        room.onAvailable(network)
        Mockito.verify(rtcEngine).reconnect()
    }

    @Test
    fun onDisconnect() {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.onEngineDisconnected("")
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.Disconnected)
    }
}