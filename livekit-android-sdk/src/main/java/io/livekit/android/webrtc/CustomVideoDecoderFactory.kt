package io.livekit.android.webrtc

import org.webrtc.EglBase
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.WrappedVideoDecoderFactory

class CustomVideoDecoderFactory(
    sharedContext: EglBase.Context?,
    private var forceSWCodec: Boolean = false,
    private var forceSWCodecs: List<String> = listOf("VP9"),
) : VideoDecoderFactory {
    private val softwareVideoDecoderFactory = SoftwareVideoDecoderFactory()
    private val wrappedVideoDecoderFactory = WrappedVideoDecoderFactory(sharedContext)


    fun setForceSWCodec(forceSWCodec: Boolean) {
        this.forceSWCodec = forceSWCodec
    }

    fun setForceSWCodecList(forceSWCodecs: List<String>) {
        this.forceSWCodecs = forceSWCodecs
    }

    override fun createDecoder(videoCodecInfo: VideoCodecInfo): VideoDecoder? {
        if (forceSWCodec) {
            return softwareVideoDecoderFactory.createDecoder(videoCodecInfo)
        }
        if (forceSWCodecs.isNotEmpty()) {
            if (forceSWCodecs.contains(videoCodecInfo.name)) {
                return softwareVideoDecoderFactory.createDecoder(videoCodecInfo)
            }
        }
        return wrappedVideoDecoderFactory.createDecoder(videoCodecInfo)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        return if (forceSWCodec && forceSWCodecs.isEmpty()) {
            softwareVideoDecoderFactory.supportedCodecs
        } else wrappedVideoDecoderFactory.supportedCodecs
    }
}
