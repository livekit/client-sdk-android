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

package io.livekit.android.room

import io.livekit.android.room.util.PeerConnectionStateObservable
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flowDelegate
import io.livekit.android.webrtc.peerconnection.executeOnRTCThread
import livekit.LivekitRtc
import livekit.org.webrtc.CandidatePairChangeEvent
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver

/**
 * @suppress
 */
class SubscriberTransportObserver(
    private val engine: RTCEngine,
    private val client: SignalClient,
) : PeerConnection.Observer, PeerConnectionStateObservable {

    var dataChannelListener: ((DataChannel) -> Unit)? = null
    var connectionChangeListener: PeerConnectionStateListener? = null

    @FlowObservable
    @get:FlowObservable
    override var connectionState by flowDelegate(PeerConnection.PeerConnectionState.NEW)
        private set

    override fun onIceCandidate(candidate: IceCandidate) {
        executeOnRTCThread {
            LKLog.v { "onIceCandidate: $candidate" }
            client.sendCandidate(candidate, LivekitRtc.SignalTarget.SUBSCRIBER)
        }
    }

    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
        executeOnRTCThread {
            val track = receiver.track() ?: return@executeOnRTCThread
            LKLog.v { "onAddTrack: ${track.kind()}, ${track.id()}, ${streams.fold("") { sum, it -> "$sum, $it" }}" }
            engine.listener?.onAddTrack(receiver, track, streams)
        }
    }

    override fun onTrack(transceiver: RtpTransceiver) {
        when (transceiver.mediaType) {
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> LKLog.v { "peerconn started receiving audio" }
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> LKLog.v { "peerconn started receiving video" }
            else -> LKLog.d { "peerconn started receiving unknown media type: ${transceiver.mediaType}" }
        }
    }

    override fun onDataChannel(channel: DataChannel) {
        executeOnRTCThread {
            dataChannelListener?.invoke(channel)
        }
    }

    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        executeOnRTCThread {
            LKLog.v { "onConnectionChange new state: $newState" }
            connectionChangeListener?.invoke(newState)
            connectionState = newState
        }
    }

    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        LKLog.v { "onIceConnection new state: $newState" }
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {
    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
    }

    override fun onAddStream(p0: MediaStream?) {
    }

    override fun onRemoveStream(p0: MediaStream?) {
    }

    override fun onRenegotiationNeeded() {
    }
}
