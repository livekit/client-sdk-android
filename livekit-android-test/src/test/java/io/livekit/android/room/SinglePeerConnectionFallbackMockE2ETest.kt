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
import io.livekit.android.events.RoomEvent
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.TestData
import io.livekit.android.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Connecting with [ConnectOptions.singlePeerConnection] to a server that has no `/rtc/v1` path:
 * the join retries on the legacy path with dual peer connections, and later reconnects stay there.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class SinglePeerConnectionFallbackMockE2ETest : MockE2ETest() {

    /**
     * Connects with single peer connection requested, answers the `/rtc/v1` handshake with 404,
     * and completes the legacy join that follows.
     */
    private suspend fun TestScope.connectWithFallback() {
        val connectJob = coroutineRule.scope.launch {
            room.connect(
                url = TestData.EXAMPLE_URL,
                token = "token",
                options = ConnectOptions(singlePeerConnection = true),
            )
        }
        room::state.flow
            .takeWhile { it != Room.State.CONNECTING }
            .collect()
        runCurrent()

        val v1Request = wsFactory.request
        assertEquals("/rtc/v1", v1Request.url.encodedPath)
        wsFactory.listener.onFailure(wsFactory.ws, IOException("404"), createFailedHandshakeResponse(v1Request, 404))
        // runCurrent rather than advanceUntilIdle: advancing virtual time would expire the
        // legacy handshake's deadline before the join response below is delivered.
        runCurrent()

        val legacyRequest = wsFactory.request
        assertTrue("expected a second websocket on the legacy path", legacyRequest !== v1Request)
        assertEquals("/rtc", legacyRequest.url.encodedPath)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(legacyRequest))
        simulateMessageFromServer(TestData.JOIN)
        connectJob.join()
        connectPeerConnection()
    }

    @Test
    fun fallsBackToLegacySignalPathWhenV1NotFound() = runTest {
        val collector = EventCollector(room.events, coroutineRule.scope)

        connectWithFallback()

        val events = collector.stopCollecting()
        assertTrue("fallback must not report a failure", events.none { it is RoomEvent.FailedToConnect })
        assertEquals(Room.State.CONNECTED, room.state)
    }

    @Test
    fun fullReconnectAfterFallbackStaysOnLegacyPath() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connectWithFallback()
        val fallbackRequest = wsFactory.request

        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)

        val reconnectRequest = wsFactory.request
        assertTrue("expected a new websocket for the reconnect", reconnectRequest !== fallbackRequest)
        assertEquals("/rtc", reconnectRequest.url.encodedPath)
        assertNull(reconnectRequest.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT))

        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(reconnectRequest))
        simulateMessageFromServer(TestData.JOIN)
        connectPeerConnection()
        testScheduler.advanceUntilIdle()

        assertEquals(Room.State.CONNECTED, room.state)
    }

    @Test
    fun softReconnectAfterFallbackStaysOnLegacyPath() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)
        connectWithFallback()
        val fallbackRequest = wsFactory.request

        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)

        val reconnectRequest = wsFactory.request
        assertTrue("expected a new websocket for the reconnect", reconnectRequest !== fallbackRequest)
        assertEquals("/rtc", reconnectRequest.url.encodedPath)
        assertEquals("1", reconnectRequest.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT))

        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(reconnectRequest))
        simulateMessageFromServer(TestData.RECONNECT)
        connectPeerConnection()
        testScheduler.advanceUntilIdle()

        assertEquals(Room.State.CONNECTED, room.state)
    }
}
