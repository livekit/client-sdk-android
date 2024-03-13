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

import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.AuthedAudioProcessingController
import io.livekit.android.audio.authenticateProcessors
import livekit.org.webrtc.AudioProcessingFactory
import livekit.org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer

class CustomAudioProcessingFactory(private var audioProcessorOptions: AudioProcessorOptions) : AuthedAudioProcessingController {

    private val externalAudioProcessor = ExternalAudioProcessingFactory()

    init {
        if (audioProcessorOptions.capturePostProcessor != null) {
            setCapturePostProcessing(audioProcessorOptions.capturePostProcessor)
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

    override fun authenticate(url: String, token: String) {
        audioProcessorOptions.authenticateProcessors(url, token)
    }

    override fun setCapturePostProcessing(processing: AudioProcessorInterface?) {
        audioProcessorOptions = audioProcessorOptions.copy(capturePostProcessor = processing)
        externalAudioProcessor.setCapturePostProcessing(
            processing.toAudioProcessing(),
        )
    }

    override fun setBypassForCapturePostProcessing(bypass: Boolean) {
        audioProcessorOptions = audioProcessorOptions.copy(capturePostBypass = bypass)
        externalAudioProcessor.setBypassFlagForCapturePost(bypass)
    }

    override fun setRenderPreProcessing(processing: AudioProcessorInterface?) {
        audioProcessorOptions = audioProcessorOptions.copy(renderPreProcessor = processing)
        externalAudioProcessor.setRenderPreProcessing(
            processing.toAudioProcessing(),
        )
    }

    override fun setBypassForRenderPreProcessing(bypass: Boolean) {
        audioProcessorOptions = audioProcessorOptions.copy(renderPreBypass = bypass)
        externalAudioProcessor.setBypassFlagForRenderPre(bypass)
    }

    private class AudioProcessingBridge(
        var audioProcessing: AudioProcessorInterface? = null,
    ) : ExternalAudioProcessingFactory.AudioProcessing {
        override fun initialize(sampleRateHz: Int, numChannels: Int) {
            audioProcessing?.initializeAudioProcessing(sampleRateHz, numChannels)
        }

        override fun reset(newRate: Int) {
            // bug in webrtc lib causes newRate to be off by a factor of 10
            // TODO: remove divide by 10 when updating libwebrtc
            audioProcessing?.resetAudioProcessing(newRate / 10)
        }

        override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer?) {
            audioProcessing?.processAudio(numBands, numFrames, buffer!!)
        }
    }

    private fun AudioProcessorInterface?.toAudioProcessing(): ExternalAudioProcessingFactory.AudioProcessing {
        return AudioProcessingBridge(this)
    }
}
