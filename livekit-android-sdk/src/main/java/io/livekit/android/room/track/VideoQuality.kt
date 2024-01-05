package io.livekit.android.room.track

import livekit.LivekitModels

enum class VideoQuality {
    LOW,
    MEDIUM,
    HIGH,
    ;

    fun toProto(): LivekitModels.VideoQuality {
        return when (this) {
            LOW -> LivekitModels.VideoQuality.LOW
            MEDIUM -> LivekitModels.VideoQuality.MEDIUM
            HIGH -> LivekitModels.VideoQuality.HIGH
        }
    }

    companion object {
        fun fromProto(quality: LivekitModels.VideoQuality): VideoQuality? {
            return when (quality) {
                LivekitModels.VideoQuality.LOW -> LOW
                LivekitModels.VideoQuality.MEDIUM -> MEDIUM
                LivekitModels.VideoQuality.HIGH -> HIGH
                LivekitModels.VideoQuality.OFF -> null
                LivekitModels.VideoQuality.UNRECOGNIZED -> null
            }
        }
    }
}
