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
import io.livekit.android.audio.AuthedAudioProcessorInterface
import io.livekit.android.util.flowDelegate
import livekit.org.webrtc.AudioProcessingFactory
import livekit.org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer

/**
 * @suppress
 */
internal class CustomAudioProcessingFactory() : AuthedAudioProcessingController {
    constructor(audioProcessorOptions: AudioProcessorOptions) : this() {
        capturePostProcessor = audioProcessorOptions.capturePostProcessor
        renderPreProcessor = audioProcessorOptions.renderPreProcessor
        bypassCapturePostProcessing = audioProcessorOptions.capturePostBypass
        bypassRenderPreProcessing = audioProcessorOptions.renderPreBypass
    }

    private val externalAudioProcessor = ExternalAudioProcessingFactory()

    override var capturePostProcessor: AudioProcessorInterface? by flowDelegate(null) { value, _ ->
        externalAudioProcessor.setCapturePostProcessing(
            value.toAudioProcessing(),
        )
    }

    override var renderPreProcessor: AudioProcessorInterface? by flowDelegate(null) { value, _ ->
        externalAudioProcessor.setRenderPreProcessing(
            value.toAudioProcessing(),
        )
    }

    override var bypassCapturePostProcessing: Boolean by flowDelegate(false) { value, _ ->
        externalAudioProcessor.setBypassFlagForCapturePost(value)
    }

    override var bypassRenderPreProcessing: Boolean by flowDelegate(false) { value, _ ->
        externalAudioProcessor.setBypassFlagForRenderPre(value)
    }

    fun getAudioProcessingFactory(): AudioProcessingFactory {
        return externalAudioProcessor
    }

    override fun authenticate(url: String, token: String) {
        (capturePostProcessor as? AuthedAudioProcessorInterface)?.authenticate(url, token)
        (renderPreProcessor as? AuthedAudioProcessorInterface)?.authenticate(url, token)
    }

    @Deprecated("Use the capturePostProcessing variable directly instead", ReplaceWith("capturePostProcessor = processing"))
    override fun setCapturePostProcessing(processing: AudioProcessorInterface?) {
        capturePostProcessor = processing
    }

    @Deprecated("Use the renderPreProcessing variable directly instead", ReplaceWith("renderPreProcessor = processing"))
    override fun setRenderPreProcessing(processing: AudioProcessorInterface?) {
        renderPreProcessor = processing
    }

    @Deprecated("Use the bypassCapturePostProcessing variable directly instead", ReplaceWith("bypassCapturePostProcessing = bypass"))
    override fun setBypassForCapturePostProcessing(bypass: Boolean) {
        bypassCapturePostProcessing = bypass
    }

    @Deprecated("Use the bypassRendererPreProcessing variable directly instead", ReplaceWith("bypassRenderPreProcessing = bypass"))
    override fun setBypassForRenderPreProcessing(bypass: Boolean) {
        bypassRenderPreProcessing = bypass
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
