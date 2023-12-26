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
import io.livekit.android.audio.AudioProcessingController
import org.webrtc.AudioProcessingFactory
import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer

class CustomAudioProcessingFactory : AudioProcessingController {
    companion object {
        @Volatile
        private var instance: CustomAudioProcessingFactory? = null
        fun sharedInstance() =
            instance ?: synchronized(this) {
                instance ?: CustomAudioProcessingFactory().also { instance = it }
            }
    }

    private constructor() {
        externalAudioProcesser = ExternalAudioProcessingFactory()
    }

    private var externalAudioProcesser: ExternalAudioProcessingFactory? = null

    fun audioProcessingFactory(): AudioProcessingFactory? {
        return externalAudioProcesser
    }

    override fun setCapturePostProcessing(processing: AudioProcessing) {
        externalAudioProcesser?.SetCapturePostProcessing(
            AudioProcessingBridge().apply {
                audioProcessing = processing
            },
        )
    }

    override fun setByPassForCapturePostProcessing(bypass: Boolean) {
        externalAudioProcesser?.SetBypassFlagForCapturePost(bypass)
    }

    override fun setRenderPreProcessing(processing: AudioProcessing) {
        externalAudioProcesser?.SetRenderPreProcessing(
            AudioProcessingBridge().apply {
                audioProcessing = processing
            },
        )
    }

    override fun setByPassForRenderPreProcessing(bypass: Boolean) {
        externalAudioProcesser?.SetBypassFlagForRenderPre(bypass)
    }

    private class AudioProcessingBridge : ExternalAudioProcessingFactory.AudioProcessing {
        var audioProcessing: AudioProcessing? = null
        override fun Initialize(sampleRateHz: Int, numChannels: Int) {
            audioProcessing?.Initialize(sampleRateHz, numChannels)
        }

        override fun Reset(newRate: Int) {
            audioProcessing?.Reset(newRate)
        }

        override fun Process(numBands: Int, numFrames: Int, buffer: ByteBuffer?) {
            audioProcessing?.Process(numBands, numFrames, buffer!!)
        }
    }
}
