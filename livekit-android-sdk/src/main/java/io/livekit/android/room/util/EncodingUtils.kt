package io.livekit.android.room.util

import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoPreset
import io.livekit.android.room.track.VideoPreset169
import io.livekit.android.room.track.VideoPreset43
import livekit.LivekitModels
import org.webrtc.RtpParameters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object EncodingUtils {

    val VIDEO_RIDS = arrayOf("q", "h", "f")

    // Note: maintain order from smallest to biggest.
    private val PRESETS_16_9 = listOf(
        VideoPreset169.QVGA,
        VideoPreset169.VGA,
        VideoPreset169.QHD,
        VideoPreset169.HD,
        VideoPreset169.FHD
    )

    // Note: maintain order from smallest to biggest.
    private val PRESETS_4_3 = listOf(
        VideoPreset43.QVGA,
        VideoPreset43.VGA,
        VideoPreset43.QHD,
        VideoPreset43.HD,
        VideoPreset43.FHD
    )

    fun determineAppropriateEncoding(width: Int, height: Int): VideoEncoding {
        val presets = presetsForResolution(width, height)

        // presets assume width is longest size
        val longestSize = max(width, height)
        val preset = presets
            .firstOrNull { it.capture.width >= longestSize }
            ?: presets.last()

        return preset.encoding
    }

    fun presetsForResolution(width: Int, height: Int): List<VideoPreset> {
        val longestSize = max(width, height)
        val shortestSize = min(width, height)
        val aspectRatio = longestSize.toFloat() / shortestSize
        return if (abs(aspectRatio - 16f / 9f) < abs(aspectRatio - 4f / 3f)) {
            PRESETS_16_9
        } else {
            PRESETS_4_3
        }
    }

    fun videoLayersFromEncodings(
        trackWidth: Int,
        trackHeight: Int,
        encodings: List<RtpParameters.Encoding>
    ): List<LivekitModels.VideoLayer> {
        return if (encodings.isEmpty()) {
            listOf(
                LivekitModels.VideoLayer.newBuilder().apply {
                    width = trackWidth
                    height = trackHeight
                    quality = LivekitModels.VideoQuality.HIGH
                    bitrate = 0
                    ssrc = 0
                }.build()
            )
        } else {
            encodings.map { encoding ->
                val scaleDownBy = encoding.scaleResolutionDownBy ?: 1.0
                var videoQuality = videoQualityForRid(encoding.rid ?: "")
                if (videoQuality == LivekitModels.VideoQuality.UNRECOGNIZED && encodings.size == 1) {
                    videoQuality = LivekitModels.VideoQuality.HIGH
                }
                LivekitModels.VideoLayer.newBuilder().apply {
                    // Internally, WebRTC casts directly to int without rounding.
                    // https://github.com/webrtc-sdk/webrtc/blob/8c7139f8e6fa19ddf2c91510c177a19746e1ded3/media/engine/webrtc_video_engine.cc#L3676
                    width = (trackWidth / scaleDownBy).toInt()
                    height = (trackHeight / scaleDownBy).toInt()
                    quality = videoQuality
                    bitrate = encoding.maxBitrateBps ?: 0
                    ssrc = 0
                }.build()
            }
        }
    }

    fun videoQualityForRid(rid: String): LivekitModels.VideoQuality {
        return when (rid) {
            "f" -> LivekitModels.VideoQuality.HIGH
            "h" -> LivekitModels.VideoQuality.MEDIUM
            "q" -> LivekitModels.VideoQuality.LOW
            else -> LivekitModels.VideoQuality.UNRECOGNIZED
        }
    }

    fun ridForVideoQuality(quality: LivekitModels.VideoQuality): String? {
        return when (quality) {
            LivekitModels.VideoQuality.HIGH -> "f"
            LivekitModels.VideoQuality.MEDIUM -> "h"
            LivekitModels.VideoQuality.LOW -> "q"
            else -> null
        }
    }
}