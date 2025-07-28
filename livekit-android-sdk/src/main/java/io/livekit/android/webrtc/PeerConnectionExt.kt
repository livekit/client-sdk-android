/*
 * Copyright 2023-2025 LiveKit, Inc.
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

package io.livekit.android.webrtc

import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnection.RTCConfiguration

/**
 * Completed state is a valid state for a connected connection, so this should be used
 * when checking for a connected state
 */
internal fun PeerConnection.isConnected(): Boolean = connectionState().isConnected()

internal fun PeerConnection.isDisconnected(): Boolean = connectionState().isDisconnected()

internal fun PeerConnection.PeerConnectionState.isConnected(): Boolean {
    return this == PeerConnection.PeerConnectionState.CONNECTED
}

internal fun PeerConnection.PeerConnectionState.isDisconnected(): Boolean {
    return when (this) {
        /**
         * [PeerConnection.PeerConnectionState.DISCONNECTED] is explicitly not included here,
         * as that is a temporary state and may return to connected state by itself.
         */
        PeerConnection.PeerConnectionState.FAILED,
        PeerConnection.PeerConnectionState.CLOSED,
        -> true

        else -> false
    }
}

fun RTCConfiguration.copy(): RTCConfiguration {
    val newConfig = RTCConfiguration(emptyList())
    newConfig.copyFrom(this)
    return newConfig
}

fun RTCConfiguration.copyFrom(config: RTCConfiguration) {
    activeResetSrtpParams = config.activeResetSrtpParams
    audioJitterBufferFastAccelerate = config.audioJitterBufferFastAccelerate
    audioJitterBufferMaxPackets = config.audioJitterBufferMaxPackets
    bundlePolicy = config.bundlePolicy
    candidateNetworkPolicy = config.candidateNetworkPolicy
    certificate = config.certificate
    continualGatheringPolicy = config.continualGatheringPolicy
    cryptoOptions = config.cryptoOptions
    disableIPv6OnWifi = config.disableIPv6OnWifi
    enableCpuOveruseDetection = config.enableCpuOveruseDetection
    enableDscp = config.enableDscp
    enableIceGatheringOnAnyAddressPorts = config.enableIceGatheringOnAnyAddressPorts
    enableImplicitRollback = config.enableImplicitRollback
    iceBackupCandidatePairPingInterval = config.iceBackupCandidatePairPingInterval
    iceCandidatePoolSize = config.iceCandidatePoolSize
    iceCheckIntervalStrongConnectivityMs = config.iceCheckIntervalStrongConnectivityMs
    iceCheckIntervalWeakConnectivityMs = config.iceCheckIntervalWeakConnectivityMs
    iceCheckMinInterval = config.iceCheckMinInterval
    iceConnectionReceivingTimeout = config.iceConnectionReceivingTimeout
    iceServers = config.iceServers
    iceTransportsType = config.iceTransportsType
    iceUnwritableMinChecks = config.iceUnwritableMinChecks
    iceUnwritableTimeMs = config.iceUnwritableTimeMs
    keyType = config.keyType
    maxIPv6Networks = config.maxIPv6Networks
    networkPreference = config.networkPreference
    offerExtmapAllowMixed = config.offerExtmapAllowMixed
    portAllocatorFlags = config.portAllocatorFlags
    portAllocatorMaxPort = config.portAllocatorMaxPort
    portAllocatorMinPort = config.portAllocatorMinPort
    presumeWritableWhenFullyRelayed = config.presumeWritableWhenFullyRelayed
    pruneTurnPorts = config.pruneTurnPorts
    rtcpMuxPolicy = config.rtcpMuxPolicy
    screencastMinBitrate = config.screencastMinBitrate
    sdpSemantics = config.sdpSemantics
    stableWritableConnectionPingIntervalMs = config.stableWritableConnectionPingIntervalMs
    stunCandidateKeepaliveIntervalMs = config.stunCandidateKeepaliveIntervalMs
    surfaceIceCandidatesOnIceTransportTypeChanged = config.surfaceIceCandidatesOnIceTransportTypeChanged
    suspendBelowMinBitrate = config.suspendBelowMinBitrate
    tcpCandidatePolicy = config.tcpCandidatePolicy
    turnCustomizer = config.turnCustomizer
    turnLoggingId = config.turnLoggingId
    turnPortPrunePolicy = config.turnPortPrunePolicy
}
