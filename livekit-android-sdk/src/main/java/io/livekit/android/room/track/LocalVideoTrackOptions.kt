package io.livekit.android.room.track

class LocalVideoTrackOptions(
    var isScreencast: Boolean = false,
    var position: CameraPosition = CameraPosition.FRONT,
    var captureParams: VideoCaptureParameter = VideoPreset.QHD.capture
)

class VideoCaptureParameter(
    val width: Int,
    val height: Int,
    val maxFps: Int,
)

class VideoEncoding(
    val maxBitrate: Int,
    val maxFps: Int,
)

enum class CameraPosition {
    FRONT,
    BACK
}

/**
 * Video presets along with suggested bitrates
 */
enum class VideoPreset(
    val capture: VideoCaptureParameter,
    val encoding: VideoEncoding,
) {
    QVGA(
        VideoCaptureParameter(320, 240, 15),
        VideoEncoding(100_000, 15),
    ),
    VGA(
        VideoCaptureParameter(640, 360, 30),
        VideoEncoding(400_000, 30),
    ),
    QHD(
        VideoCaptureParameter(960, 540, 30),
        VideoEncoding(700_000, 30),
    ),
    HD(
        VideoCaptureParameter(1280, 720, 30),
        VideoEncoding(2_000_000, 30),
    ),
    FHD(
        VideoCaptureParameter(1920, 1080, 30),
        VideoEncoding(4_000_000, 30),
    )

}