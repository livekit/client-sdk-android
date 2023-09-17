package io.livekit.android.videoencodedecode

import com.github.ajalt.timberkt.Timber
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
        Timber.v { "actual supported codecs: ${supportedCodecs.map { it.name }}" }

        val filteredCodecs = supportedCodecs.filter { codecWhitelist?.contains(it.name) ?: true }.toTypedArray()
        Timber.v { "filtered supported codecs: ${filteredCodecs.map { it.name }}" }

        return filteredCodecs
    }
}
