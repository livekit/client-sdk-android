/*
 * Copyright 2023-2024 LiveKit, Inc.
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

import android.content.Context
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.audio.NoopCommunicationWorkaround
import io.livekit.android.e2ee.E2EEManager
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.EventListenable
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.memory.CloseableManager
import io.livekit.android.room.network.NetworkCallbackManagerImpl
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.test.assert.assertIsClassList
import io.livekit.android.test.coroutines.TestCoroutineRule
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.MockAudioDeviceModule
import io.livekit.android.test.mock.MockAudioProcessingController
import io.livekit.android.test.mock.MockEglBase
import io.livekit.android.test.mock.MockLKObjects
import io.livekit.android.test.mock.MockNetworkCallbackRegistry
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.room.util.MockConnectionWarmer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import livekit.LivekitRtc.JoinResponse
import livekit.org.webrtc.EglBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

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

    @Mock
    lateinit var e2EEManagerFactory: E2EEManager.Factory

    @Mock
    lateinit var regionUrlProviderFactory: RegionUrlProvider.Factory

    lateinit var networkCallbackRegistry: MockNetworkCallbackRegistry

    var eglBase: EglBase = MockEglBase()

    val localParticipantFactory = object : LocalParticipant.Factory {
        override fun create(dynacast: Boolean): LocalParticipant {
            return Mockito.mock(LocalParticipant::class.java)
                .apply {
                    whenever(this.events).thenReturn(
                        object : EventListenable<ParticipantEvent> {
                            override val events: SharedFlow<ParticipantEvent> = MutableSharedFlow()
                        },
                    )
                }
        }
    }

    lateinit var room: Room

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        networkCallbackRegistry = MockNetworkCallbackRegistry()
        room = Room(
            context = context,
            engine = rtcEngine,
            eglBase = eglBase,
            localParticipantFactory = localParticipantFactory,
            defaultsManager = DefaultsManager(),
            defaultDispatcher = coroutineRule.dispatcher,
            ioDispatcher = coroutineRule.dispatcher,
            audioHandler = NoAudioHandler(),
            closeableManager = CloseableManager(),
            e2EEManagerFactory = e2EEManagerFactory,
            communicationWorkaround = NoopCommunicationWorkaround(),
            audioProcessingController = MockAudioProcessingController(),
            lkObjects = MockLKObjects.get(),
            networkCallbackManagerFactory = { networkCallback: NetworkCallback ->
                NetworkCallbackManagerImpl(networkCallback, networkCallbackRegistry)
            },
            audioDeviceModule = MockAudioDeviceModule(),
            regionUrlProviderFactory = regionUrlProviderFactory,
            connectionWarmer = MockConnectionWarmer(),
        )
    }

    suspend fun connect(joinResponse: JoinResponse = TestData.JOIN.join) {
        rtcEngine.stub {
            onBlocking { rtcEngine.join(any(), any(), anyOrNull(), anyOrNull()) }
                .doSuspendableAnswer {
                    room.onJoinResponse(joinResponse)
                    joinResponse
                }
        }
        rtcEngine.stub {
            onBlocking { rtcEngine.client }
                .doReturn(Mockito.mock(SignalClient::class.java))
        }

        room.connect(
            url = TestData.EXAMPLE_URL,
            token = "",
        )
    }

    @Test
    fun connectTest() = runTest {
        connect()
        val roomInfo = TestData.JOIN.join.room

        assertEquals(roomInfo.name, room.name)
        assertEquals(Room.Sid(roomInfo.sid), room.sid)
        assertEquals(roomInfo.metadata, room.metadata)
        assertEquals(roomInfo.activeRecording, room.isRecording)
    }

    @Test
    fun roomUpdate() = runTest {
        connect()
        val update = TestData.ROOM_UPDATE.roomUpdate.room

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.onRoomUpdate(update)
        val events = eventCollector.stopCollecting()

        assertEquals(update.sid, room.sid?.sid)
        assertEquals(update.metadata, room.metadata)
        assertEquals(update.activeRecording, room.isRecording)

        assertIsClassList(
            listOf(
                RoomEvent.RoomMetadataChanged::class.java,
                RoomEvent.RecordingStatusChanged::class.java,
            ),
            events,
        )
    }

    @Test
    fun onConnectionAvailableWillReconnect() = runTest {
        connect()

        val network = Mockito.mock(Network::class.java)

        networkCallbackRegistry.networkCallbacks.forEach { callback ->
            callback.onLost(network)
        }
        networkCallbackRegistry.networkCallbacks.forEach { callback ->
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
    fun getSidSuspendsUntilPopulated() = runTest {
        val job = async {
            room.getSid()
        }

        assertFalse(job.isCompleted)
        connect()
        assertFalse(job.isCompleted)
        val update = TestData.ROOM_UPDATE.roomUpdate.room
        room.onRoomUpdate(update)

        advanceUntilIdle()
        assertTrue(job.isCompleted)
        val sid = job.await()

        assertEquals(update.sid, sid.sid)
    }
}
