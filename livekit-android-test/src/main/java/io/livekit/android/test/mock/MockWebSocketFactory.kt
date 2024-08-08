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

import com.google.protobuf.MessageLite
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.toOkioByteString
import livekit.LivekitModels
import livekit.LivekitRtc.LeaveRequest
import livekit.LivekitRtc.SignalRequest
import livekit.LivekitRtc.SignalResponse
import livekit.LivekitRtc.TrackPublishedResponse
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

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
        this.ws = MockWebSocket(request, listener) { byteString ->
            val signalRequest = SignalRequest.parseFrom(byteString.toPBByteString())
            handleSignalRequest(signalRequest)
        }
        this.listener = listener
        this.request = request

        return ws
    }

    val defaultSignalRequestHandler: SignalRequestHandler = { signalRequest -> defaultHandleSignalRequest(signalRequest) }

    private val signalRequestHandlers = mutableListOf(defaultSignalRequestHandler)

    /**
     * Adds a handler to the front of the list.
     */
    fun registerSignalRequestHandler(handler: SignalRequestHandler) {
        signalRequestHandlers.add(0, handler)
    }

    fun unregisterSignalRequestHandler(handler: SignalRequestHandler) {
        signalRequestHandlers.remove(handler)
    }

    private fun handleSignalRequest(signalRequest: SignalRequest) {
        for (handler in signalRequestHandlers) {
            if (handler.invoke(signalRequest)) {
                break
            }
        }
    }

    private fun defaultHandleSignalRequest(signalRequest: SignalRequest): Boolean {
        when (signalRequest.messageCase) {
            SignalRequest.MessageCase.ADD_TRACK -> {
                val addTrack = signalRequest.addTrack
                val trackPublished = with(SignalResponse.newBuilder()) {
                    trackPublished = with(TrackPublishedResponse.newBuilder()) {
                        cid = addTrack.cid
                        track = if (addTrack.type == LivekitModels.TrackType.AUDIO) {
                            TestData.LOCAL_AUDIO_TRACK
                        } else {
                            TestData.LOCAL_VIDEO_TRACK
                        }
                        build()
                    }
                    build()
                }
                receiveMessage(trackPublished)
                return true
            }

            SignalRequest.MessageCase.LEAVE -> {
                val leaveResponse = with(SignalResponse.newBuilder()) {
                    leave = with(LeaveRequest.newBuilder()) {
                        canReconnect = false
                        reason = LivekitModels.DisconnectReason.CLIENT_INITIATED
                        build()
                    }
                    build()
                }
                receiveMessage(leaveResponse)
                return true
            }

            else -> {
                return false
            }
        }
    }

    var onOpen: ((MockWebSocketFactory) -> Unit)? = null

    fun receiveMessage(message: MessageLite) {
        receiveMessage(message.toOkioByteString())
    }

    fun receiveMessage(byteString: ByteString) {
        listener.onMessage(ws, byteString)
    }
}

typealias SignalRequestHandler = (SignalRequest) -> Boolean
