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

package io.livekit.android

import io.livekit.android.room.ProtocolVersion
import io.livekit.android.room.Room
import livekit.org.webrtc.PeerConnection

/**
 * Options for using with [Room.connect].
 */
data class ConnectOptions(
    /** Auto subscribe to room tracks upon connect, defaults to true */
    val autoSubscribe: Boolean = true,

    /**
     * A user-provided list of ice servers. This will be merged into
     * the ice servers in [rtcConfig] if it is also provided.
     */
    val iceServers: List<PeerConnection.IceServer>? = null,

    /**
     * A user-provided RTCConfiguration to override options.
     *
     * Note: LiveKit requires [PeerConnection.SdpSemantics.UNIFIED_PLAN] and
     * [PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY].
     */
    val rtcConfig: PeerConnection.RTCConfiguration? = null,
    /**
     * capture and publish audio track on connect, defaults to false
     */
    val audio: Boolean = false,
    /**
     * capture and publish video track on connect, defaults to false
     */
    val video: Boolean = false,

    /**
     * the protocol version to use with the server.
     */
    val protocolVersion: ProtocolVersion = ProtocolVersion.v13,
) {
    internal var reconnect: Boolean = false
    internal var participantSid: String? = null
}
