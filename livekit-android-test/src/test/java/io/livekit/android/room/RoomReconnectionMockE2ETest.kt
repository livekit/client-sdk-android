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

import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.room.track.createMockLocalAudioTrack
import io.livekit.android.test.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitRtc
import livekit.org.webrtc.PeerConnection
import org.junit.Assert
import org.junit.Assert.assertEquals
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

    @Test
    fun softReconnectSendsSyncState() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)

        connect()
        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
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
    fun softReconnectConfiguration() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)
        connect()
        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()

        val rtcConfig = getSubscriberPeerConnection().rtcConfig
        assertEquals(PeerConnection.IceTransportsType.RELAY, rtcConfig.iceTransportsType)

        val sentIceServers = TestData.RECONNECT.reconnect.iceServersList
            .map { server -> server.toWebrtc() }
        assertEquals(sentIceServers, rtcConfig.iceServers)
    }

    @Test
    fun fullReconnectRepublishesTracks() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        // publish track
        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(),
        )

        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
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
