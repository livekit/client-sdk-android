package io.livekit.android.room

enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    RECONNECTING;
}