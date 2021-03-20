package io.livekit.android


data class ConnectOptions(
    val isSecure: Boolean = true,
    val sendAudio: Boolean = true,
    val sendVideo: Boolean = true,
)