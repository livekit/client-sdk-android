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

package io.livekit.android.test.mock

import livekit.org.webrtc.CandidatePairChangeEvent
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.PeerConnection

/**
 * Creates an [IceCandidate] with a [serverUrl] and [adapterType], which the public constructor does not allow.
 */
fun createIceCandidate(
    sdp: String,
    sdpMid: String = "0",
    sdpMLineIndex: Int = 0,
    serverUrl: String = "",
    adapterType: PeerConnection.AdapterType = PeerConnection.AdapterType.UNKNOWN,
): IceCandidate = IceCandidate::class.java
    .getDeclaredConstructor(
        String::class.java,
        Int::class.javaPrimitiveType,
        String::class.java,
        String::class.java,
        PeerConnection.AdapterType::class.java,
    )
    .apply { isAccessible = true }
    .newInstance(sdpMid, sdpMLineIndex, sdp, serverUrl, adapterType)

/**
 * Creates a [CandidatePairChangeEvent], whose constructor is package-private and normally only called from native code.
 */
fun createCandidatePairChangeEvent(
    local: IceCandidate,
    remote: IceCandidate,
    reason: String = "",
    lastDataReceivedMs: Int = 0,
    estimatedDisconnectedTimeMs: Int = 0,
): CandidatePairChangeEvent = CandidatePairChangeEvent::class.java
    .getDeclaredConstructor(
        IceCandidate::class.java,
        IceCandidate::class.java,
        Int::class.javaPrimitiveType,
        String::class.java,
        Int::class.javaPrimitiveType,
    )
    .apply { isAccessible = true }
    .newInstance(local, remote, lastDataReceivedMs, reason, estimatedDisconnectedTimeMs)
