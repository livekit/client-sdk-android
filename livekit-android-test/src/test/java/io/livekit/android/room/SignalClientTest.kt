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

import io.livekit.android.stats.NetworkInfo
import io.livekit.android.stats.NetworkType
import io.livekit.android.test.BaseTest
import io.livekit.android.test.mock.MockWebSocketFactory
import io.livekit.android.test.mock.TestData.EXAMPLE_URL
import io.livekit.android.test.mock.TestData.JOIN
import io.livekit.android.test.mock.TestData.OFFER
import io.livekit.android.test.mock.TestData.PONG
import io.livekit.android.test.mock.TestData.RECONNECT
import io.livekit.android.test.mock.TestData.ROOM_UPDATE
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import livekit.LivekitRtc
import livekit.org.webrtc.SessionDescription
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.inOrder
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.times

@ExperimentalCoroutinesApi
class SignalClientTest : BaseTest() {

    lateinit var wsFactory: MockWebSocketFactory
    lateinit var client: SignalClient

    @Mock
    lateinit var listener: SignalClient.Listener

    @Mock
    lateinit var okHttpClient: OkHttpClient

    @Before
    fun setup() {
        wsFactory = MockWebSocketFactory()
        client = SignalClient(
            wsFactory,
            Json,
            okHttpClient = okHttpClient,
            ioDispatcher = coroutineRule.dispatcher,
            networkInfo = object : NetworkInfo {
                override fun getNetworkType() = NetworkType.WIFI
            },
        )
        client.listener = listener
    }

    private fun createOpenResponse(request: Request): Response {
        return Response.Builder()
            .request(request)
            .code(200)
            .protocol(Protocol.HTTP_2)
            .message("")
            .build()
    }

    /**
     * Supply the needed websocket messages to finish a join call.
     */
    private fun connectWebsocketAndJoin() {
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, JOIN.toOkioByteString())
    }

    @Test
    fun joinAndResponse() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }

        connectWebsocketAndJoin()

        val response = job.await()
        assertEquals(true, client.isConnected)
        assertEquals(response, JOIN.join)
    }

    @Test
    fun reconnect() = runTest {
        val job = async {
            client.reconnect(EXAMPLE_URL, "", "participant_sid")
        }

        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, RECONNECT.toOkioByteString())

        job.await()
        assertEquals(true, client.isConnected)
    }

    @Test
    fun joinFailure() = runTest {
        var failed = false
        val job = async {
            try {
                client.join(EXAMPLE_URL, "")
            } catch (e: Exception) {
                failed = true
            }
        }

        client.onFailure(wsFactory.ws, Exception(), null)
        job.await()

        assertTrue(failed)
    }

    @Test
    fun listenerNotCalledUntilOnReady() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }

        connectWebsocketAndJoin()
        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())

        job.await()

        Mockito.verifyNoInteractions(listener)
    }

    @Test
    fun listenerCalledAfterOnReady() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        connectWebsocketAndJoin()
        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())

        job.await()
        client.onReadyForResponses()
        Mockito.verify(listener)
            .onOffer(argThat { type == SessionDescription.Type.OFFER && description == OFFER.offer.sdp })
    }

    /**
     * [WebSocketListener.onFailure] does not call through to
     * [WebSocketListener.onClosed]. Ensure that listener is called properly.
     */
    @Test
    fun listenerNotifiedAfterFailure() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        connectWebsocketAndJoin()
        job.await()

        client.onFailure(wsFactory.ws, Exception(), null)

        Mockito.verify(listener)
            .onClose(any(), any())
    }

    /**
     * Ensure responses that come in before [SignalClient.onReadyForResponses] are queued.
     */
    @Test
    fun queuedResponses() = runTest {
        val inOrder = inOrder(listener)
        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        connectWebsocketAndJoin()
        job.await()

        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())
        client.onMessage(wsFactory.ws, ROOM_UPDATE.toOkioByteString())
        client.onMessage(wsFactory.ws, ROOM_UPDATE.toOkioByteString())

        client.onReadyForResponses()

        inOrder.verify(listener).onOffer(any())
        inOrder.verify(listener, times(2)).onRoomUpdate(any())
    }

    @Test
    fun sendRequest() = runTest {
        val job = async { client.join(EXAMPLE_URL, "") }
        connectWebsocketAndJoin()
        job.await()

        client.sendMuteTrack("sid", true)

        val ws = wsFactory.ws

        assertEquals(1, ws.sentRequests.size)
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(ws.sentRequests[0].toPBByteString())
            .build()

        assertTrue(sentRequest.hasMute())
    }

    @Test
    fun queuedRequests() = runTest {
        client.sendMuteTrack("sid", true)
        client.sendMuteTrack("sid", true)
        client.sendMuteTrack("sid", true)

        val job = async { client.join(EXAMPLE_URL, "") }
        connectWebsocketAndJoin()
        job.await()

        val ws = wsFactory.ws
        assertEquals(3, ws.sentRequests.size)
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(ws.sentRequests[0].toPBByteString())
            .build()

        assertTrue(sentRequest.hasMute())
    }

    @Test
    fun queuedRequestsWhileReconnecting() = runTest {
        client.sendMuteTrack("sid", true)
        client.sendMuteTrack("sid", true)
        client.sendMuteTrack("sid", true)

        val job = async { client.reconnect(EXAMPLE_URL, "", "participant_sid") }
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, RECONNECT.toOkioByteString())
        job.await()

        val ws = wsFactory.ws

        // Wait until peer connection is connected to send requests.
        assertEquals(0, ws.sentRequests.size)

        client.onPCConnected()

        assertEquals(3, ws.sentRequests.size)
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(ws.sentRequests[0].toPBByteString())
            .build()

        assertTrue(sentRequest.hasMute())
    }

    @Test
    fun pingTest() = runTest {
        val joinResponseWithPing = with(JOIN.toBuilder()) {
            join = with(join.toBuilder()) {
                pingInterval = 10
                pingTimeout = 20
                build()
            }
            build()
        }

        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, joinResponseWithPing.toOkioByteString())
        job.await()
        val originalWs = wsFactory.ws
        assertFalse(originalWs.isClosed)

        testScheduler.advanceTimeBy(15 * 1000)
        assertTrue(
            originalWs.sentRequests.any { requestString ->
                val sentRequest = LivekitRtc.SignalRequest.newBuilder()
                    .mergeFrom(requestString.toPBByteString())
                    .build()

                return@any sentRequest.hasPing()
            },
        )

        client.onMessage(wsFactory.ws, PONG.toOkioByteString())

        testScheduler.advanceTimeBy(10 * 1000)
        assertFalse(originalWs.isClosed)
    }

    @Test
    fun pingTimeoutTest() = runTest {
        val joinResponseWithPing = with(JOIN.toBuilder()) {
            join = with(join.toBuilder()) {
                pingInterval = 10
                pingTimeout = 20
                build()
            }
            build()
        }

        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, joinResponseWithPing.toOkioByteString())
        job.await()
        val originalWs = wsFactory.ws
        assertFalse(originalWs.isClosed)

        testScheduler.advanceUntilIdle()

        assertTrue(originalWs.isClosed)
    }

    // mock data
    companion object
}
