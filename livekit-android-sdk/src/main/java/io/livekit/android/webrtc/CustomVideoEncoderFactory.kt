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

package io.livekit.android.webrtc

import org.webrtc.EglBase
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory

open class CustomVideoEncoderFactory(
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
