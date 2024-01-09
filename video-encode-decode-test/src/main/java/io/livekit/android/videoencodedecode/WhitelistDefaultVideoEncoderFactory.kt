/*
 * Copyright 2024 LiveKit, Inc.
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

package io.livekit.android.videoencodedecode

import com.github.ajalt.timberkt.Timber
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.VideoCodecInfo

class WhitelistDefaultVideoEncoderFactory(
    eglContext: EglBase.Context?,
    enableIntelVp8Encoder: Boolean,
    enableH264HighProfile: Boolean
) : DefaultVideoEncoderFactory(eglContext, enableIntelVp8Encoder, enableH264HighProfile), WhitelistEncoderFactory {

    override var codecWhitelist: List<String>? = null

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val supportedCodecs = super.getSupportedCodecs()
        Timber.v { "actual supported codecs: ${supportedCodecs.map { it.name }}" }

        val filteredCodecs = supportedCodecs.filter { codecWhitelist?.contains(it.name) ?: true }.toTypedArray()
        Timber.v { "filtered supported codecs: ${filteredCodecs.map { it.name }}" }

        return filteredCodecs
    }
}
