package io.livekit.android.room.track

data class LocalAudioTrackOptions(
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true,
    val autoGainControl: Boolean = true,
    val highPassFilter: Boolean = true,
    val typingNoiseDetection: Boolean = true,
)
