/*
 * Copyright 2023 LiveKit, Inc.
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

package io.livekit.android.mock

import io.livekit.android.util.toOkioByteString
import io.livekit.android.util.toPBByteString
import livekit.LivekitModels
import livekit.LivekitRtc
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
        this.ws = MockWebSocket(request, listener) { byteString ->
            val signalRequest = LivekitRtc.SignalRequest.parseFrom(byteString.toPBByteString())
            if (signalRequest.hasAddTrack()) {
                val addTrack = signalRequest.addTrack
                val trackPublished = with(LivekitRtc.SignalResponse.newBuilder()) {
                    trackPublished = with(LivekitRtc.TrackPublishedResponse.newBuilder()) {
                        cid = addTrack.cid
                        if (addTrack.type == LivekitModels.TrackType.AUDIO) {
                            track = TestData.LOCAL_AUDIO_TRACK
                        } else {
                            track = TestData.LOCAL_VIDEO_TRACK
                        }
                        build()
                    }
                    build()
                }
                this.listener.onMessage(this.ws, trackPublished.toOkioByteString())
            }
        }
        this.listener = listener
        this.request = request

        onOpen?.invoke(this)
        return ws
    }

    var onOpen: ((MockWebSocketFactory) -> Unit)? = null
}
