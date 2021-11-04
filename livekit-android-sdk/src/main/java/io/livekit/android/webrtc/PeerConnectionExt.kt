package io.livekit.android.webrtc

import org.webrtc.PeerConnection

/**
 * Completed state is a valid state for a connected connection, so this should be used
 * when checking for a connected state
 */
internal fun PeerConnection.isConnected(): Boolean {
    return when (iceConnectionState()) {
        PeerConnection.IceConnectionState.CONNECTED,
        PeerConnection.IceConnectionState.COMPLETED -> true
        else -> false
    }
}