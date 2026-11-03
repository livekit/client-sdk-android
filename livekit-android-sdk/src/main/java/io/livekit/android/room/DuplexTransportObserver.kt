/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room

import io.livekit.android.util.LKLog
import io.livekit.android.webrtc.peerconnection.RTCThreadToken
import io.livekit.android.webrtc.peerconnection.executeOnRTCThread
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver

internal class DuplexTransportObserver(engine: RTCEngine, client: SignalClient, rtcThreadToken: RTCThreadToken) :
    PublisherTransportObserver(engine, client, rtcThreadToken) {
    var dataChannelListener: ((DataChannel) -> Unit)? = null

    override fun onTrack(transceiver: RtpTransceiver?) {
        when (transceiver?.mediaType) {
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> LKLog.v { "peerconn started receiving audio" }
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> LKLog.v { "peerconn started receiving video" }
            else -> LKLog.d { "peerconn started receiving unknown media type: ${transceiver?.mediaType}" }
        }
    }

    override fun onDataChannel(dataChannel: DataChannel?) {
        executeOnRTCThread(rtcThreadToken) {
            dataChannel?.let {
                dataChannelListener?.invoke(it)
            }
        }
    }

    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
        executeOnRTCThread(rtcThreadToken) {
            if (receiver != null && streams != null) {
                val track = receiver.track() ?: return@executeOnRTCThread
                LKLog.v { "onAddTrack: ${track.kind()}, ${track.id()}, ${streams.fold("") { sum, it -> "$sum, $it" }}" }
                engine.listener?.onAddTrack(receiver, track, streams)
            }
        }
    }
}
