package io.livekit.android.mock

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString

class MockWebSocket(private val request: Request) : WebSocket {

    var isClosed = false
    override fun cancel() {
        isClosed = true
    }

    override fun close(code: Int, reason: String?): Boolean {
        val willClose = !isClosed
        isClosed = true
        return willClose
    }

    override fun queueSize(): Long = 0

    override fun request(): Request = request

    override fun send(text: String): Boolean = !isClosed

    override fun send(bytes: ByteString): Boolean = !isClosed
}