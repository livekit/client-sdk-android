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

package io.livekit.android.webrtc.peerconnection

import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpSender
import livekit.org.webrtc.RtpTransceiver

/**
 * Objects obtained through [PeerConnection] are transient,
 * and should not be kept in memory. Calls to these methods
 * dispose all existing objects in the tree and refresh with
 * new updated objects:
 *
 * * [PeerConnection.getTransceivers]
 * * [PeerConnection.getReceivers]
 * * [PeerConnection.getSenders]
 *
 * For this reason, any object gotten through the PeerConnection
 * should instead be looked up through the PeerConnection as needed.
 */
internal abstract class PeerConnectionResource<T>(val parentPeerConnection: PeerConnection) {
    abstract fun get(): T?
}

internal class RtpTransceiverResource(parentPeerConnection: PeerConnection, private val senderId: String) : PeerConnectionResource<RtpTransceiver>(parentPeerConnection) {
    override fun get() = executeBlockingOnRTCThread {
        parentPeerConnection.transceivers.firstOrNull { t -> t.sender.id() == senderId }
    }
}

internal class RtpReceiverResource(parentPeerConnection: PeerConnection, private val receiverId: String) : PeerConnectionResource<RtpReceiver>(parentPeerConnection) {
    override fun get() = executeBlockingOnRTCThread {
        parentPeerConnection.receivers.firstOrNull { r -> r.id() == receiverId }
    }
}

internal class RtpSenderResource(parentPeerConnection: PeerConnection, private val senderId: String) : PeerConnectionResource<RtpSender>(parentPeerConnection) {
    override fun get() = executeBlockingOnRTCThread {
        parentPeerConnection.senders.firstOrNull { s -> s.id() == senderId }
    }
}
