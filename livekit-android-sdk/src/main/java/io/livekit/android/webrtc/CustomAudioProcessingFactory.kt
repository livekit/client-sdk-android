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

import io.livekit.android.audio.AudioProcessingController
import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.audio.AudioProcessorOptions
import livekit.org.webrtc.AudioProcessingFactory
import livekit.org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer

class CustomAudioProcessingFactory(private val audioProcessorOptions: AudioProcessorOptions) : AudioProcessingController {

    private val externalAudioProcessor = ExternalAudioProcessingFactory()

    init {
        if (audioProcessorOptions.capturePostProcessor != null) {
            setCapturePostProcessing(audioProcessorOptions.capturePostProcessor)
            setBypassForCapturePostProcessing(audioProcessorOptions.capturePostBypass)
        } else {
            setCapturePostProcessing(null)
            setBypassForCapturePostProcessing(false)
        }
        if (audioProcessorOptions.renderPreProcessor != null) {
            setRenderPreProcessing(audioProcessorOptions.renderPreProcessor)
            setBypassForRenderPreProcessing(audioProcessorOptions.renderPreBypass)
        } else {
            setRenderPreProcessing(null)
            setBypassForRenderPreProcessing(false)
        }
    }

    fun getAudioProcessingFactory(): AudioProcessingFactory {
        return externalAudioProcessor
    }

    override fun setCapturePostProcessing(processing: AudioProcessorInterface?) {
        externalAudioProcessor.setCapturePostProcessing(
            processing.toAudioProcessing(),
        )
    }

    override fun setBypassForCapturePostProcessing(bypass: Boolean) {
        externalAudioProcessor.setBypassFlagForCapturePost(bypass)
    }

    override fun setRenderPreProcessing(processing: AudioProcessorInterface?) {
        externalAudioProcessor.setRenderPreProcessing(
            processing.toAudioProcessing(),
        )
    }

    override fun setBypassForRenderPreProcessing(bypass: Boolean) {
        externalAudioProcessor.setBypassFlagForRenderPre(bypass)
    }

    private class AudioProcessingBridge(
        var audioProcessing: AudioProcessorInterface? = null,
    ) : ExternalAudioProcessingFactory.AudioProcessing {
        override fun initialize(sampleRateHz: Int, numChannels: Int) {
            audioProcessing?.initializeAudioProcessing(sampleRateHz, numChannels)
        }

        override fun reset(newRate: Int) {
            audioProcessing?.resetAudioProcessing(newRate)
        }

        override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer?) {
            audioProcessing?.processAudio(numBands, numFrames, buffer!!)
        }
    }

    private fun AudioProcessorInterface?.toAudioProcessing(): ExternalAudioProcessingFactory.AudioProcessing {
        return AudioProcessingBridge(this)
    }
}
