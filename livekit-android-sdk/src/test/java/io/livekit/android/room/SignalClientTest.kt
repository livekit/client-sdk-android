package io.livekit.android.room

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import livekit.LivekitRtc
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.verify

class SignalClientTest {

    lateinit var wsFactory: MockWebsocketFactory
    lateinit var client: SignalClient
    lateinit var listener: SignalClient.Listener

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
        client = SignalClient(
            wsFactory,
            JsonFormat.parser(),
            JsonFormat.printer(),
            Json,
            useJson = false
        )
        listener = Mockito.mock(SignalClient.Listener::class.java)
        client.listener = listener
    }

    fun join() {
        client.join("http://www.example.com", "", null)
    }

    @Test
    fun joinAndResponse() {
        join()
        client.onOpen(
            wsFactory.ws,
            Response.Builder()
                .request(wsFactory.request)
                .code(200)
                .protocol(Protocol.HTTP_2)
                .message("")
                .build()
        )

        val response = with(LivekitRtc.SignalResponse.newBuilder()) {
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
        val byteArray = response.toByteArray()
        val byteString = byteArray.toByteString(0, byteArray.size)

        client.onMessage(wsFactory.ws, byteString)

        verify(listener).onJoin(response.join)
    }
}