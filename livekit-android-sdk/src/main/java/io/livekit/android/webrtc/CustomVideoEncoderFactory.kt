package io.livekit.android.webrtc

import org.webrtc.EglBase
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory

class CustomVideoEncoderFactory(
    sharedContext: EglBase.Context?,
    enableIntelVp8Encoder: Boolean,
    enableH264HighProfile: Boolean,
    private var forceSWCodec: Boolean = false,
    private var forceSWCodecs: List<String> = listOf("VP9"),
) : VideoEncoderFactory {
    private val softwareVideoEncoderFactory = SoftwareVideoEncoderFactory()
    private val simulcastVideoEncoderFactoryWrapper: SimulcastVideoEncoderFactoryWrapper

    init {
        simulcastVideoEncoderFactoryWrapper =
            SimulcastVideoEncoderFactoryWrapper(sharedContext, enableIntelVp8Encoder, enableH264HighProfile)
    }

    fun setForceSWCodec(forceSWCodec: Boolean) {
        this.forceSWCodec = forceSWCodec
    }

    fun setForceSWCodecList(forceSWCodecs: List<String>) {
        this.forceSWCodecs = forceSWCodecs
    }

    override fun createEncoder(videoCodecInfo: VideoCodecInfo): VideoEncoder? {
        if (forceSWCodec) {
            return softwareVideoEncoderFactory.createEncoder(videoCodecInfo)
        }
        if (forceSWCodecs.isNotEmpty()) {
            if (forceSWCodecs.contains(videoCodecInfo.name)) {
                return softwareVideoEncoderFactory.createEncoder(videoCodecInfo)
            }
        }
        return simulcastVideoEncoderFactoryWrapper.createEncoder(videoCodecInfo)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        return if (forceSWCodec && forceSWCodecs.isEmpty()) {
            softwareVideoEncoderFactory.supportedCodecs
        } else {
            simulcastVideoEncoderFactoryWrapper.supportedCodecs
        }
    }
}
