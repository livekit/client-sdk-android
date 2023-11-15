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

import io.livekit.android.audio.AudioProcessing
import org.webrtc.AudioProcessingFactory
import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer

class CustomAudioProcessingFactory {
    constructor() {
        externalAudioProcesser = ExternalAudioProcessingFactory()
    }
    class AudioProcessingBridge: ExternalAudioProcessingFactory.AudioProcessing {
        var audioProcessing: AudioProcessing? = null
        override fun Initialize(sampleRateHz: Int, numChannels: Int) {
            audioProcessing?.Initialize(sampleRateHz, numChannels)
        }

        override fun Reset(newRate: Int) {
            audioProcessing?.Reset(newRate)
        }

        override fun Process(numBans: Int, numFrames: Int, buffer: ByteBuffer?) {
            audioProcessing?.Process(numBans, numFrames, buffer!!)
        }
    }

    private var externalAudioProcesser: ExternalAudioProcessingFactory? = null

    fun audioProcessingFactory(): AudioProcessingFactory? {
        return externalAudioProcesser
    }

    fun setCapturePostProcessing(processing: AudioProcessing) {
        externalAudioProcesser?.SetCapturePostProcessing(
            AudioProcessingBridge().apply {
                audioProcessing = processing
            },
        )
    }

    fun setRenderPreProcessing(processing: AudioProcessing) {
        externalAudioProcesser?.SetRenderPreProcessing(
            AudioProcessingBridge().apply {
                audioProcessing = processing
            },
        )
    }
}
