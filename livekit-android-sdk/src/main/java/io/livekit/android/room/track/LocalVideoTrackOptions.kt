package io.livekit.android.room.track

import org.webrtc.RtpParameters

data class LocalVideoTrackOptions(
    val isScreencast: Boolean = false,
    /**
     * Preferred deviceId to capture from. If not set or found,
     * will prefer a camera according to [position]
     */
    val deviceId: String? = null,
    val position: CameraPosition? = CameraPosition.FRONT,
    val captureParams: VideoCaptureParameter = VideoPreset169.QHD.capture
)

data class VideoCaptureParameter(
    val width: Int,
    val height: Int,
    val maxFps: Int,
)

data class VideoEncoding(
    val maxBitrate: Int,
    val maxFps: Int,
) {
    fun toRtpEncoding(
        rid: String? = null,
        scaleDownBy: Double = 1.0,
    ): RtpParameters.Encoding {
        return RtpParameters.Encoding(rid, true, scaleDownBy).apply {
            numTemporalLayers = 1
            maxBitrateBps = maxBitrate
            maxFramerate = maxFps

            // only set on the full track
            if (scaleDownBy == 1.0) {
                networkPriority = 3 // high, from priority.h in webrtc
                bitratePriority = 4.0
            } else {
                networkPriority = 1 // low, from priority.h in webrtc
                bitratePriority = 1.0
            }

        }
    }
}

enum class VideoCodec(val codecName: String) {
    VP8("vp8"),
    H264("h264"),
}

enum class CameraPosition {
    FRONT,
    BACK
}

interface VideoPreset {
    val capture: VideoCaptureParameter
    val encoding: VideoEncoding
}

/**
 * 16:9 Video presets along with suggested bitrates
 */
enum class VideoPreset169(
    override val capture: VideoCaptureParameter,
    override val encoding: VideoEncoding,
) : VideoPreset {
    QVGA(
        VideoCaptureParameter(320, 180, 15),
        VideoEncoding(125_000, 15),
    ),
    VGA(
        VideoCaptureParameter(640, 360, 30),
        VideoEncoding(400_000, 30),
    ),
    QHD(
        VideoCaptureParameter(960, 540, 30),
        VideoEncoding(800_000, 30),
    ),
    HD(
        VideoCaptureParameter(1280, 720, 30),
        VideoEncoding(2_500_000, 30),
    ),
    FHD(
        VideoCaptureParameter(1920, 1080, 30),
        VideoEncoding(4_000_000, 30),
    )
}

/**
 * 4:3 Video presets along with suggested bitrates
 */
enum class VideoPreset43(
    override val capture: VideoCaptureParameter,
    override val encoding: VideoEncoding,
) : VideoPreset {
    QVGA(
        VideoCaptureParameter(240, 180, 15),
        VideoEncoding(100_000, 15),
    ),
    VGA(
        VideoCaptureParameter(480, 360, 30),
        VideoEncoding(320_000, 30),
    ),
    QHD(
        VideoCaptureParameter(720, 540, 30),
        VideoEncoding(640_000, 30),
    ),
    HD(
        VideoCaptureParameter(960, 720, 30),
        VideoEncoding(2_000_000, 30),
    ),
    FHD(
        VideoCaptureParameter(1440, 1080, 30),
        VideoEncoding(3_200_000, 30),
    )
}