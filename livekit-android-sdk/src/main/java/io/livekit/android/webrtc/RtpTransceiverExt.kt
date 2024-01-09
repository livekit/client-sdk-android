/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android.webrtc

import io.livekit.android.dagger.CapabilitiesGetter
import io.livekit.android.util.LKLog
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.RtpCapabilities
import livekit.org.webrtc.RtpTransceiver

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
