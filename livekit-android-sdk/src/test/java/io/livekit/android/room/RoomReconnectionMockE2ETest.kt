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

/**
 * For tests that only target one reconnection type.
 *
 * Tests that cover all connection types should be put in [RoomReconnectionTypesMockE2ETest].
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomReconnectionMockE2ETest : MockE2ETest() {

    private fun prepareForReconnect() {
        wsFactory.onOpen = {
            wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
            val softReconnectParam = wsFactory.request.url
                .queryParameter(SignalClient.CONNECT_QUERY_RECONNECT)
                ?.toIntOrNull()
                ?: 0

            if (softReconnectParam == 0) {
                simulateMessageFromServer(SignalClientTest.JOIN)
            }
        }
    }

    @Test
    fun softReconnectSendsSyncState() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)

        connect()
        prepareForReconnect()
        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
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