package io.livekit.android.room

import com.google.protobuf.util.JsonFormat
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.serialization.json.Json
import livekit.LivekitRtc
import okhttp3.*
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@ExperimentalCoroutinesApi
class SignalClientTest {

    lateinit var wsFactory: MockWebsocketFactory
    lateinit var client: SignalClient
    lateinit var listener: SignalClient.Listener
    lateinit var okHttpClient: OkHttpClient

    class MockWebsocketFactory : WebSocket.Factory {
        lateinit var ws: WebSocket
        lateinit var request: Request
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            ws = Mockito.mock(WebSocket::class.java)
            this.request = request
            return ws
        }
    }

    @Before
    fun setup() {
        wsFactory = MockWebsocketFactory()
        okHttpClient = Mockito.mock(OkHttpClient::class.java)
        client = SignalClient(
            wsFactory,
            JsonFormat.parser(),
            JsonFormat.printer(),
            Json,
            useJson = false,
            okHttpClient = okHttpClient,
        )
        listener = Mockito.mock(SignalClient.Listener::class.java)
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

    @Test
    fun joinAndResponse() {
        val job = TestCoroutineScope().async {
            client.join("http://www.example.com", "", null)
        }
        client.onOpen(
            wsFactory.ws,
            createOpenResponse(wsFactory.request)
        )
        client.onMessage(wsFactory.ws, JOIN.toOkioByteString())

        runBlockingTest {
            val response = job.await()
            Assert.assertEquals(response, JOIN.join)
        }
    }

    @Test
    fun reconnect() {
        val job = TestCoroutineScope().async {
            client.reconnect("http://www.example.com", "")
        }
        client.onOpen(
            wsFactory.ws,
            createOpenResponse(wsFactory.request)
        )
        runBlockingTest {
            job.await()
        }
    }

    // mock data
    companion object {
        private val EXAMPLE_URL = "http://www.example.com"

        private val JOIN = with(LivekitRtc.SignalResponse.newBuilder()) {
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
    }
}