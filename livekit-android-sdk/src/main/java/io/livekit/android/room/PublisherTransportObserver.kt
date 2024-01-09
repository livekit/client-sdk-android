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

package io.livekit.android.room

import io.livekit.android.util.LKLog
import io.livekit.android.webrtc.peerconnection.executeOnRTCThread
import livekit.LivekitRtc
import livekit.org.webrtc.*

/**
 * @suppress
 */
class PublisherTransportObserver(
    private val engine: RTCEngine,
    private val client: SignalClient,
) : PeerConnection.Observer, PeerConnectionTransport.Listener {

    var connectionChangeListener: ((newState: PeerConnection.PeerConnectionState) -> Unit)? = null

    override fun onIceCandidate(iceCandidate: IceCandidate?) {
        executeOnRTCThread {
            val candidate = iceCandidate ?: return@executeOnRTCThread
            LKLog.v { "onIceCandidate: $candidate" }
            client.sendCandidate(candidate, target = LivekitRtc.SignalTarget.PUBLISHER)
        }
    }

    override fun onRenegotiationNeeded() {
        executeOnRTCThread {
            engine.negotiatePublisher()
        }
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        LKLog.v { "onIceConnection new state: $newState" }
    }

    override fun onOffer(sd: SessionDescription) {
        executeOnRTCThread {
            client.sendOffer(sd)
        }
    }

    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        executeOnRTCThread {
            LKLog.v { "onConnection new state: $newState" }
            connectionChangeListener?.invoke(newState)
        }
    }

    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
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

    override fun onDataChannel(dataChannel: DataChannel?) {
    }

    override fun onTrack(transceiver: RtpTransceiver?) {
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
    }
}
