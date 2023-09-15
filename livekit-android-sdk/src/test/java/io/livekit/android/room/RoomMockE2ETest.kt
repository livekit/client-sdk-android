/*
 * Copyright 2023 LiveKit, Inc.
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
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.platform.app.InstrumentationRegistry
import io.livekit.android.MockE2ETest
import io.livekit.android.assert.assertIsClassList
import io.livekit.android.events.*
import io.livekit.android.mock.MockAudioStreamTrack
import io.livekit.android.mock.MockMediaStream
import io.livekit.android.mock.MockRtpReceiver
import io.livekit.android.mock.TestData
import io.livekit.android.mock.createMediaStreamId
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import io.livekit.android.util.toOkioByteString
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowConnectivityManager

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
        val roomUpdate = SignalClientTest.ROOM_UPDATE
        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.ROOM_UPDATE.toOkioByteString())
        val events = eventCollector.stopCollecting()

        assertEquals(
            roomUpdate.roomUpdate.room.metadata,
            room.metadata
        )
        assertEquals(
            roomUpdate.roomUpdate.room.activeRecording,
            room.isRecording
        )
        assertIsClassList(
            listOf(
                RoomEvent.RoomMetadataChanged::class.java,
                RoomEvent.RecordingStatusChanged::class.java,
            ),
            events
        )
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

        assertIsClassList(
            listOf(
                RoomEvent.ParticipantConnected::class.java,
                RoomEvent.TrackPublished::class.java,
                RoomEvent.TrackPublished::class.java,
            ),
            events
        )
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

        assertIsClassList(
            listOf(
                RoomEvent.TrackUnpublished::class.java,
                RoomEvent.TrackUnpublished::class.java,
                RoomEvent.ParticipantDisconnected::class.java,
            ),
            events
        )
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
    fun trackStreamStateChanged() = runTest {
        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.PARTICIPANT_JOIN.toOkioByteString()
        )

        // We intentionally don't emit if the track isn't subscribed, so need to
        // add track.
        room.onAddTrack(
            receiver = MockRtpReceiver.create(),
            track = MockAudioStreamTrack(),
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
            MockRtpReceiver.create(),
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

        val connectivityManager = InstrumentationRegistry.getInstrumentation()
            .context
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowConnectivityManager: ShadowConnectivityManager = shadowOf(connectivityManager)

        shadowConnectivityManager.networkCallbacks.forEach { callback ->
            callback.onLost(network)
        }
        shadowConnectivityManager.networkCallbacks.forEach { callback ->
            callback.onAvailable(network)
        }

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

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.Disconnected)

    }

    @Test
    fun disconnectCleansLocalParticipant() = runTest {
        connect()

        room.localParticipant.publishAudioTrack(
            LocalAudioTrack(
                "",
                MockAudioStreamTrack(id = SignalClientTest.LOCAL_TRACK_PUBLISHED.trackPublished.cid)
            )
        )

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.disconnect()
        val events = eventCollector.stopCollecting()

        assertEquals(2, events.size)
        assertEquals(true, events[0] is RoomEvent.TrackUnpublished)
        assertEquals(true, events[1] is RoomEvent.Disconnected)
    }

    @Test
    fun disconnectCleansUpParticipants() = runTest {
        connect()

        room.onUpdateParticipants(SignalClientTest.PARTICIPANT_JOIN.update.participantsList)
        room.onAddTrack(
            MockRtpReceiver.create(),
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

        assertIsClassList(
            listOf(
                RoomEvent.TrackUnsubscribed::class.java,
                RoomEvent.TrackUnpublished::class.java,
                RoomEvent.TrackUnpublished::class.java,
                RoomEvent.ParticipantDisconnected::class.java,
                RoomEvent.Disconnected::class.java
            ),
            events
        )
        Assert.assertTrue(room.remoteParticipants.isEmpty())
    }

    @Test
    fun serverDisconnectReason() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.LEAVE.toOkioByteString())
        val events = eventCollector.stopCollecting()
        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.Disconnected)
        assertEquals(SignalClientTest.LEAVE.leave.reason.convert(), (events[0] as RoomEvent.Disconnected).reason)
    }

    @Test
    fun clientDisconnectReason() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.disconnect()
        val events = eventCollector.stopCollecting()
        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.Disconnected)
        assertEquals(DisconnectReason.CLIENT_INITIATED, (events[0] as RoomEvent.Disconnected).reason)
    }

    @Test
    fun connectAfterDisconnect() = runTest {
        connect()
        room.disconnect()
        connect()
        Assert.assertEquals(room.state, Room.State.CONNECTED)
    }
}
