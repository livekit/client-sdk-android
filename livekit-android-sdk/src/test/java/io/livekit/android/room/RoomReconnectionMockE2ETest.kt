package io.livekit.android.room

import io.livekit.android.MockE2ETest
import io.livekit.android.assert.assertIsClassList
import io.livekit.android.events.EventCollector
import io.livekit.android.events.FlowCollector
import io.livekit.android.events.RoomEvent
import io.livekit.android.mock.MockAudioStreamTrack
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.util.flow
import io.livekit.android.util.toPBByteString
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import livekit.LivekitRtc
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomReconnectionMockE2ETest : MockE2ETest() {

    private fun prepareForReconnect(softReconnect: Boolean = false) {
        wsFactory.onOpen = {
            wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
            if (!softReconnect) {
                simulateMessageFromServer(SignalClientTest.JOIN)
            }
        }
    }

    @Test
    fun reconnectFromPeerConnectionDisconnect() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        val stateCollector = FlowCollector(room::state.flow, coroutineRule.scope)
        prepareForReconnect()
        disconnectPeerConnection()
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val events = eventCollector.stopCollecting()
        val states = stateCollector.stopCollecting()

        assertIsClassList(
            listOf(
                RoomEvent.Reconnecting::class.java,
                RoomEvent.Reconnected::class.java,
            ),
            events
        )

        assertEquals(
            listOf(
                Room.State.CONNECTED,
                Room.State.RECONNECTING,
                Room.State.CONNECTED,
            ),
            states
        )
    }

    @Test
    fun reconnectFromWebSocketFailure() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        val stateCollector = FlowCollector(room::state.flow, coroutineRule.scope)
        prepareForReconnect()
        wsFactory.ws.cancel()
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val events = eventCollector.stopCollecting()
        val states = stateCollector.stopCollecting()

        assertIsClassList(
            listOf(
                RoomEvent.Reconnecting::class.java,
                RoomEvent.Reconnected::class.java,
            ),
            events
        )

        assertEquals(
            listOf(
                Room.State.CONNECTED,
                Room.State.RECONNECTING,
                Room.State.CONNECTED,
            ),
            states
        )
    }

    @Test
    fun softReconnectSendsSyncState() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)

        connect()
        prepareForReconnect()
        disconnectPeerConnection()
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val sentRequests = wsFactory.ws.sentRequests
        val sentSyncState = sentRequests.any { requestString ->
            val sentRequest = LivekitRtc.SignalRequest.newBuilder()
                .mergeFrom(requestString.toPBByteString())
                .build()

            return@any sentRequest.hasSyncState()
        }

        Assert.assertTrue(sentSyncState)
    }

    @Test
    fun fullReconnectRepublishesTracks() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        // publish track
        val publishJob = launch {
            room.localParticipant.publishAudioTrack(
                LocalAudioTrack(
                    "",
                    MockAudioStreamTrack(id = SignalClientTest.LOCAL_TRACK_PUBLISHED.trackPublished.cid)
                )
            )
        }
        simulateMessageFromServer(SignalClientTest.LOCAL_TRACK_PUBLISHED)
        publishJob.join()

        prepareForReconnect()
        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
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
        Assert.assertTrue(sentAddTrack)
    }

}