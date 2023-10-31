/*
 * Copyright 2023 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
    val captureParams: VideoCaptureParameter = VideoPreset169.QHD.capture,
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
    VP9("vp9"),
    AV1("av1");

    companion object {
        fun fromCodecName(codecName: String): VideoCodec {
            return VideoCodec.values().first { it.codecName.equals(codecName, ignoreCase = true) }
        }
    }
}

enum class CameraPosition {
    FRONT,
    BACK,
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
    H90(
        VideoCaptureParameter(160, 90, 15),
        VideoEncoding(90_000, 15),
    ),
    H180(
        VideoCaptureParameter(320, 180, 15),
        VideoEncoding(160_000, 15),
    ),
    H216(
        VideoCaptureParameter(384, 216, 15),
        VideoEncoding(180_000, 15),
    ),
    H360(
        VideoCaptureParameter(640, 360, 30),
        VideoEncoding(450_000, 30),
    ),
    H540(
        VideoCaptureParameter(960, 540, 30),
        VideoEncoding(800_000, 30),
    ),
    H720(
        VideoCaptureParameter(1280, 720, 30),
        VideoEncoding(1_700_000, 30),
    ),
    H1080(
        VideoCaptureParameter(1920, 1080, 30),
        VideoEncoding(3_000_000, 30),
    ),
    H1440(
        VideoCaptureParameter(2560, 1440, 30),
        VideoEncoding(5_000_000, 30),
    ),
    H2160(
        VideoCaptureParameter(3840, 2160, 30),
        VideoEncoding(8_000_000, 30),
    ),

    @Deprecated("QVGA is deprecated, use H180 instead")
    QVGA(
        VideoCaptureParameter(320, 180, 15),
        VideoEncoding(125_000, 15),
    ),

    @Deprecated("VGA is deprecated, use H360 instead")
    VGA(
        VideoCaptureParameter(640, 360, 30),
        VideoEncoding(400_000, 30),
    ),

    @Deprecated("QHD is deprecated, use H540 instead")
    QHD(
        VideoCaptureParameter(960, 540, 30),
        VideoEncoding(800_000, 30),
    ),

    @Deprecated("HD is deprecated, use H720 instead")
    HD(
        VideoCaptureParameter(1280, 720, 30),
        VideoEncoding(2_500_000, 30),
    ),

    @Deprecated("FHD is deprecated, use H1080 instead")
    FHD(
        VideoCaptureParameter(1920, 1080, 30),
        VideoEncoding(4_000_000, 30),
    ),
}

/**
 * 4:3 Video presets along with suggested bitrates
 */
enum class VideoPreset43(
    override val capture: VideoCaptureParameter,
    override val encoding: VideoEncoding,
) : VideoPreset {
    H120(
        VideoCaptureParameter(160, 120, 15),
        VideoEncoding(70_000, 15),
    ),
    H180(
        VideoCaptureParameter(240, 180, 15),
        VideoEncoding(125_000, 15),
    ),
    H240(
        VideoCaptureParameter(320, 240, 15),
        VideoEncoding(140_000, 15),
    ),
    H360(
        VideoCaptureParameter(480, 360, 30),
        VideoEncoding(330_000, 30),
    ),
    H480(
        VideoCaptureParameter(640, 480, 30),
        VideoEncoding(500_000, 30),
    ),
    H540(
        VideoCaptureParameter(720, 540, 30),
        VideoEncoding(600_000, 30),
    ),
    H720(
        VideoCaptureParameter(960, 720, 30),
        VideoEncoding(1_300_000, 30),
    ),
    H1080(
        VideoCaptureParameter(1440, 1080, 30),
        VideoEncoding(2_300_000, 30),
    ),
    H1440(
        VideoCaptureParameter(1920, 1440, 30),
        VideoEncoding(3_800_000, 30),
    ),

    @Deprecated("QVGA is deprecated, use H120 instead")
    QVGA(
        VideoCaptureParameter(240, 180, 15),
        VideoEncoding(100_000, 15),
    ),

    @Deprecated("VGA is deprecated, use H360 instead")
    VGA(
        VideoCaptureParameter(480, 360, 30),
        VideoEncoding(320_000, 30),
    ),

    @Deprecated("QHD is deprecated, use H540 instead")
    QHD(
        VideoCaptureParameter(720, 540, 30),
        VideoEncoding(640_000, 30),
    ),

    @Deprecated("HD is deprecated, use H720 instead")
    HD(
        VideoCaptureParameter(960, 720, 30),
        VideoEncoding(2_000_000, 30),
    ),

    @Deprecated("FHD is deprecated, use H1080 instead")
    FHD(
        VideoCaptureParameter(1440, 1080, 30),
        VideoEncoding(3_200_000, 30),
    ),
}
