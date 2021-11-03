package io.livekit.android.room

import com.google.protobuf.util.JsonFormat
import io.livekit.android.mock.MockWebsocketFactory
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.serialization.json.Json
import livekit.LivekitRtc
import okhttp3.*
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.argThat
import org.webrtc.SessionDescription

@ExperimentalCoroutinesApi
class SignalClientTest {

    lateinit var wsFactory: MockWebsocketFactory
    lateinit var client: SignalClient
    lateinit var listener: SignalClient.Listener
    lateinit var okHttpClient: OkHttpClient

    lateinit var coroutineDispatcher: TestCoroutineDispatcher
    lateinit var coroutineScope: TestCoroutineScope

    @Before
    fun setup() {
        coroutineDispatcher = TestCoroutineDispatcher()
        coroutineScope = TestCoroutineScope(coroutineDispatcher)
        wsFactory = MockWebsocketFactory()
        okHttpClient = Mockito.mock(OkHttpClient::class.java)
        client = SignalClient(
            wsFactory,
            JsonFormat.parser(),
            JsonFormat.printer(),
            Json,
            useJson = false,
            okHttpClient = okHttpClient,
            ioDispatcher = coroutineDispatcher
        )
        listener = Mockito.mock(SignalClient.Listener::class.java)
        client.listener = listener
    }

    @After
    fun tearDown() {
        coroutineScope.cleanupTestCoroutines()
    }

    private fun createOpenResponse(request: Request): Response {
        return Response.Builder()
            .request(request)
            .code(200)
            .protocol(Protocol.HTTP_2)
            .message("")
            .build()
    }

    @Test
    fun joinAndResponse() {
        val job = coroutineScope.async {
            client.join(EXAMPLE_URL, "", null)
        }

        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, JOIN.toOkioByteString())

        runBlockingTest {
            val response = job.await()
            Assert.assertEquals(response, JOIN.join)
        }
    }

    @Test
    fun reconnect() {
        val job = coroutineScope.async {
            client.reconnect(EXAMPLE_URL, "")
        }

        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))

        runBlockingTest {
            job.await()
        }
    }

    @Test
    fun listenerNotCalledUntilOnReady() {
        val listener = Mockito.mock(SignalClient.Listener::class.java)
        client.listener = listener

        val job = coroutineScope.async {
            client.join(EXAMPLE_URL, "", null)
        }
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, JOIN.toOkioByteString())
        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())

        runBlockingTest { job.await() }

        Mockito.verifyNoInteractions(listener)
    }

    @Test
    fun listenerCalledAfterOnReady() {
        val listener = Mockito.mock(SignalClient.Listener::class.java)
        client.listener = listener

        val job = coroutineScope.async {
            client.join(EXAMPLE_URL, "", null)
        }
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, JOIN.toOkioByteString())
        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())

        runBlockingTest { job.await() }
        client.onReady()
        Mockito.verify(listener)
            .onOffer(argThat { type == SessionDescription.Type.OFFER && description == OFFER.offer.sdp })
    }

    // mock data
    companion object {
        private const val EXAMPLE_URL = "http://www.example.com"

        val JOIN = with(LivekitRtc.SignalResponse.newBuilder()) {
            join = with(joinBuilder) {
                room = with(roomBuilder) {
                    name = "roomname"
                    sid = "sid"
                    build()
                }
                build()
            }
            build()
        }

        val OFFER = with(LivekitRtc.SignalResponse.newBuilder()) {
            offer = with(offerBuilder) {
                sdp = ""
                type = "offer"
                build()
            }
            build()
        }

        val ROOM_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
            roomUpdate = with(roomUpdateBuilder) {
                room = with(roomBuilder) {
                    metadata = "metadata"
                    build()
                }
                build()
            }
            build()
        }
    }
}