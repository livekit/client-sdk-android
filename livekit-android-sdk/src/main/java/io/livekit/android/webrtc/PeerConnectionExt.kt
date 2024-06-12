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
        PeerConnection.PeerConnectionState.CLOSED -> true
        else -> false
    }
}

fun RTCConfiguration.copy(): RTCConfiguration {
    val newConfig = RTCConfiguration(emptyList())
    newConfig.copyFrom(this)
    return newConfig
}

fun RTCConfiguration.copyFrom(config: RTCConfiguration) {
    iceTransportsType = config.iceTransportsType
    iceServers = config.iceServers
    bundlePolicy = config.bundlePolicy
    certificate = config.certificate
    rtcpMuxPolicy = config.rtcpMuxPolicy
    tcpCandidatePolicy = config.tcpCandidatePolicy
    candidateNetworkPolicy = config.candidateNetworkPolicy
    audioJitterBufferMaxPackets = config.audioJitterBufferMaxPackets
    audioJitterBufferFastAccelerate = config.audioJitterBufferFastAccelerate
    iceConnectionReceivingTimeout = config.iceConnectionReceivingTimeout
    iceBackupCandidatePairPingInterval = config.iceBackupCandidatePairPingInterval
    keyType = config.keyType
    continualGatheringPolicy = config.continualGatheringPolicy
    iceCandidatePoolSize = config.iceCandidatePoolSize

    pruneTurnPorts = config.pruneTurnPorts
    turnPortPrunePolicy = config.turnPortPrunePolicy
    presumeWritableWhenFullyRelayed = config.presumeWritableWhenFullyRelayed
    surfaceIceCandidatesOnIceTransportTypeChanged = config.surfaceIceCandidatesOnIceTransportTypeChanged
    iceCheckIntervalStrongConnectivityMs = config.iceCheckIntervalStrongConnectivityMs
    iceCheckIntervalWeakConnectivityMs = config.iceCheckIntervalWeakConnectivityMs
    iceCheckMinInterval = config.iceCheckMinInterval
    iceUnwritableTimeMs = config.iceUnwritableTimeMs
    iceUnwritableMinChecks = config.iceUnwritableMinChecks
    stunCandidateKeepaliveIntervalMs = config.stunCandidateKeepaliveIntervalMs
    stableWritableConnectionPingIntervalMs = config.stableWritableConnectionPingIntervalMs
    disableIPv6OnWifi = config.disableIPv6OnWifi
    maxIPv6Networks = config.maxIPv6Networks
    enableDscp = config.enableDscp
    enableCpuOveruseDetection = config.enableCpuOveruseDetection
    suspendBelowMinBitrate = config.suspendBelowMinBitrate
    screencastMinBitrate = config.screencastMinBitrate
    networkPreference = config.networkPreference
    sdpSemantics = config.sdpSemantics
    turnCustomizer = config.turnCustomizer
    activeResetSrtpParams = config.activeResetSrtpParams
    cryptoOptions = config.cryptoOptions
    turnLoggingId = config.turnLoggingId
    enableImplicitRollback = config.enableImplicitRollback
    offerExtmapAllowMixed = config.offerExtmapAllowMixed
    enableIceGatheringOnAnyAddressPorts = config.enableIceGatheringOnAnyAddressPorts
}
