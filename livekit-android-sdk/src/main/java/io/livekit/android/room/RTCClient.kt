package io.livekit.android.room

import com.github.ajalt.timberkt.Timber
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import javax.inject.Inject

internal class RTCClient
@Inject
constructor(
    private val websocketFactory: WebSocket.Factory,
) : WebSocketListener() {

    private var isConnected = false
    private var currentWs: WebSocket? = null
    fun connect(
        host: String,
        token: String,
        isSecure: Boolean,
    ) {
        val protocol = if (isSecure) "wss" else "ws"

        val wsUrlString = "$protocol://$host/rtc?access_token=$token"
        Timber.i { "connecting to $wsUrlString" }

        val request = Request.Builder()
            .url(wsUrlString)
            .build()
        currentWs = websocketFactory.newWebSocket(request, this)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        super.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        super.onMessage(webSocket, text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        super.onMessage(webSocket, bytes)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        super.onClosed(webSocket, code, reason)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        super.onClosing(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        super.onFailure(webSocket, t, response)
    }

}