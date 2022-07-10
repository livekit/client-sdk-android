package io.livekit.android.mock

import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class MockWebSocketFactory : WebSocket.Factory {
    /**
     * The most recently created [WebSocket].
     */
    lateinit var ws: MockWebSocket

    /**
     * The request used to create [ws]
     */
    lateinit var request: Request

    /**
     * The listener associated with [ws]
     */
    lateinit var listener: WebSocketListener
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        this.ws = MockWebSocket(request, listener)
        this.listener = listener
        this.request = request

        onOpen?.invoke(this)
        return ws
    }

    var onOpen: ((MockWebSocketFactory) -> Unit)? = null
}