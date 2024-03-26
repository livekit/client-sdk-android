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

package io.livekit.android.test.mock

import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.IOException

class MockWebSocket(
    private val request: Request,
    private val listener: WebSocketListener,
    private val onSend: ((ByteString) -> Unit)?,
) : WebSocket {

    var isClosed = false
        private set

    private val mutableSentRequests = mutableListOf<ByteString>()
    val sentRequests: List<ByteString>
        get() = mutableSentRequests

    override fun cancel() {
        isClosed = true
        listener.onFailure(this, IOException("cancelled"), null)
    }

    override fun close(code: Int, reason: String?): Boolean {
        val willClose = !isClosed
        if (!willClose) {
            return false
        }
        isClosed = true
        listener.onClosing(this, code, reason ?: "")
        listener.onClosed(this, code, reason ?: "")
        return willClose
    }

    override fun queueSize(): Long = 0

    override fun request(): Request = request

    override fun send(text: String): Boolean = !isClosed

    override fun send(bytes: ByteString): Boolean {
        if (isClosed) {
            return false
        }
        mutableSentRequests.add(bytes)
        onSend?.invoke(bytes)
        return !isClosed
    }

    fun clearRequests() {
        mutableSentRequests.clear()
    }
}
