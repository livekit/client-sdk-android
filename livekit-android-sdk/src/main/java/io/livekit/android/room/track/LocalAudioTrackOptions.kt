package io.livekit.android.room.track

class LocalAudioTrackOptions(
    var noiseSuppression: Boolean = true,
    var echoCancellation: Boolean = true,
    var autoGainControl: Boolean = true,
    var highPassFilter: Boolean = true,
    var typingNoiseDetection: Boolean = true,
)
