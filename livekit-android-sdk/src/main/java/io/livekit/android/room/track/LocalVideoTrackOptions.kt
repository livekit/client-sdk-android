package io.livekit.android.room.track

class LocalVideoTrackOptions(
    var isScreencast: Boolean = false,
    var position: CameraPosition = CameraPosition.FRONT,
    var captureParams: VideoCaptureParameter = VideoPreset169.QHD.capture
)

data class VideoCaptureParameter(
    val width: Int,
    val height: Int,
    val maxFps: Int,
)

data class VideoEncoding(
    val maxBitrate: Int,
    val maxFps: Int,
)

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