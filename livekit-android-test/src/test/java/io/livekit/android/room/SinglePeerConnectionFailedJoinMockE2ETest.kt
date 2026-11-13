/*
 * Copyright 2026 LiveKit, Inc.
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

import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import livekit.org.webrtc.MockPeerConnectionFactory
import livekit.org.webrtc.PeerConnection.IceConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Connecting with [ConnectOptions.singlePeerConnection] when the `/rtc/v1` join is rejected: the
 * publisher created ahead of the join does not outlive it.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class SinglePeerConnectionFailedJoinMockE2ETest : MockE2ETest() {

    private val options = ConnectOptions(singlePeerConnection = true)

    private fun peerConnections(): List<MockPeerConnection> {
        return (component.peerConnectionFactory() as MockPeerConnectionFactory).peerConnections
    }

    /**
     * Answers the pending `/rtc/v1` handshake with 403 and waits for [join] to end.
     */
    private suspend fun TestScope.rejectJoin(join: Job) {
        runCurrent()
        val request = wsFactory.request
        assertEquals("/rtc/v1", request.url.encodedPath)
        wsFactory.listener.onFailure(wsFactory.ws, IOException("403"), createFailedHandshakeResponse(request, 403))
        join.join()
    }

    @Test
    fun failedJoinClosesTheEarlyPublisher() = runTest {
        var failure: Throwable? = null
        val connectJob = coroutineRule.scope.launch {
            failure = runCatching { room.connect(TestData.EXAMPLE_URL, "token", options) }.exceptionOrNull()
        }

        rejectJoin(connectJob)

        assertTrue("expected the join to fail, got $failure", failure is RoomException.ConnectException)
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertEquals(IceConnectionState.CLOSED, peerConnections().single().iceConnectionState())
    }

    @Test
    fun joinClosesTheEarlyPublisherOfAFailedJoin() = runTest {
        val engine = component.rtcEngine()
        val firstJoin = coroutineRule.scope.launch {
            runCatching { engine.join(TestData.EXAMPLE_URL, "token", options, RoomOptions()) }
        }
        rejectJoin(firstJoin)
        val firstPublisher = peerConnections().single()
        assertNotEquals(IceConnectionState.CLOSED, firstPublisher.iceConnectionState())

        val secondJoin = coroutineRule.scope.launch {
            runCatching { engine.join(TestData.EXAMPLE_URL, "token", options, RoomOptions()) }
        }
        runCurrent()

        assertEquals(2, peerConnections().size)
        assertEquals(IceConnectionState.CLOSED, firstPublisher.iceConnectionState())

        rejectJoin(secondJoin)
        engine.close()
        assertEquals(IceConnectionState.CLOSED, peerConnections().last().iceConnectionState())
    }
}
