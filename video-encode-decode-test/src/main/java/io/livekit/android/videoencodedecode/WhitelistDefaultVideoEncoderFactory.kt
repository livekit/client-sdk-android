package io.livekit.android.videoencodedecode

import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.VideoCodecInfo

class WhitelistDefaultVideoEncoderFactory(
    eglContext: EglBase.Context?,
    enableIntelVp8Encoder: Boolean,
    enableH264HighProfile: Boolean
) : DefaultVideoEncoderFactory(eglContext, enableIntelVp8Encoder, enableH264HighProfile), WhitelistEncoderFactory {

    override var codecWhitelist: List<String>? = null

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val supportedCodecs = super.getSupportedCodecs()

        return supportedCodecs.filter { codecWhitelist?.contains(it.name) ?: true }.toTypedArray()
    }
}