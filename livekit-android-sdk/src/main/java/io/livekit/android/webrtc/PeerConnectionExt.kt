package io.livekit.android.webrtc

import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.RTCConfiguration

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

internal fun RTCConfiguration.copy(): RTCConfiguration {
    val newConfig = RTCConfiguration(emptyList())
    newConfig.copyFrom(this)
    return newConfig
}

internal fun RTCConfiguration.copyFrom(config: RTCConfiguration) {
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
    disableIpv6 = config.disableIpv6
    enableDscp = config.enableDscp
    enableCpuOveruseDetection = config.enableCpuOveruseDetection
    suspendBelowMinBitrate = config.suspendBelowMinBitrate
    screencastMinBitrate = config.screencastMinBitrate
    combinedAudioVideoBwe = config.combinedAudioVideoBwe
    networkPreference = config.networkPreference
    sdpSemantics = config.sdpSemantics
    turnCustomizer = config.turnCustomizer
    activeResetSrtpParams = config.activeResetSrtpParams
    allowCodecSwitching = config.allowCodecSwitching
    cryptoOptions = config.cryptoOptions
    turnLoggingId = config.turnLoggingId
    enableImplicitRollback = config.enableImplicitRollback
    offerExtmapAllowMixed = config.offerExtmapAllowMixed
}