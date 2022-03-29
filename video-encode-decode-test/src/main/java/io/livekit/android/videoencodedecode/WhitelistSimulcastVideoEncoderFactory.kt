package io.livekit.android.videoencodedecode

import io.livekit.android.webrtc.SimulcastVideoEncoderFactoryWrapper
import org.webrtc.EglBase
import org.webrtc.VideoCodecInfo

class WhitelistSimulcastVideoEncoderFactory(
    sharedContext: EglBase.Context?,
    enableIntelVp8Encoder: Boolean,
    enableH264HighProfile: Boolean
) : SimulcastVideoEncoderFactoryWrapper(sharedContext, enableIntelVp8Encoder, enableH264HighProfile),
    WhitelistEncoderFactory {
    override var codecWhitelist: List<String>? = null

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val supportedCodecs = super.getSupportedCodecs()

        return supportedCodecs.filter { codecWhitelist?.contains(it.name) ?: true }.toTypedArray()
    }
}