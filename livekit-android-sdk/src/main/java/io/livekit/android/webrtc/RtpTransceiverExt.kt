package io.livekit.android.webrtc

import io.livekit.android.dagger.CapabilitiesGetter
import io.livekit.android.util.LKLog
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpCapabilities
import org.webrtc.RtpTransceiver

internal fun RtpTransceiver.sortVideoCodecPreferences(targetCodec: String, capabilitiesGetter: CapabilitiesGetter) {

    val capabilities = capabilitiesGetter(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
    LKLog.v { "capabilities:" }
    capabilities.codecs.forEach { codec ->
        LKLog.v { "codec: ${codec.name}, ${codec.kind}, ${codec.mimeType}, ${codec.parameters}, ${codec.preferredPayloadType}" }
    }

    val matched = mutableListOf<RtpCapabilities.CodecCapability>()
    val partialMatched = mutableListOf<RtpCapabilities.CodecCapability>()
    val unmatched = mutableListOf<RtpCapabilities.CodecCapability>()

    for (codec in capabilities.codecs) {
        val mimeType = codec.mimeType.lowercase()
        if (mimeType == "audio/opus") {
            matched.add(codec)
            continue
        }

        if (mimeType != "video/$targetCodec") {
            unmatched.add(codec)
            continue
        }
        // for h264 codecs that have sdpFmtpLine available, use only if the
        // profile-level-id is 42e01f for cross-browser compatibility
        if (targetCodec == "h264") {
            if (codec.parameters["profile-level-id"] == "42e01f") {
                matched.add(codec)
            } else {
                partialMatched.add(codec)
            }
            continue
        } else {
            matched.add(codec)
        }
    }
    setCodecPreferences(matched.plus(partialMatched).plus(unmatched))
}
