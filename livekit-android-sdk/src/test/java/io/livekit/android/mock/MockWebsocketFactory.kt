package io.livekit.android.mock

import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.mockito.Mockito

class MockWebsocketFactory : WebSocket.Factory {
    lateinit var ws: WebSocket
    lateinit var request: Request
    lateinit var listener: WebSocketListener
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        this.ws = Mockito.mock(WebSocket::class.java)
        this.listener = listener
        this.request = request
        return ws
    }
}