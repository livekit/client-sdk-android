package io.livekit.android.webrtc

import org.webrtc.PeerConnection

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
