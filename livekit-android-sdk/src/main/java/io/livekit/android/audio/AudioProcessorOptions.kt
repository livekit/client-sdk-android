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

package io.livekit.android.audio

class AudioProcessorOptions {
    enum class AudioProcessorType {
        CapturePost,
        RenderPre,
    }

    /**
     * The audio processing to be used.
     */
    var process: Map<AudioProcessorType, AudioProcessorInterface> = mapOf()

    /**
     * Whether to bypass the audio processing.
     */
    var bypass: Map<AudioProcessorType, Boolean> = mapOf()

    fun getCapturePostProcessor(): AudioProcessorInterface? {
        return process[AudioProcessorType.CapturePost]
    }

    fun getCapturePostBypass(): Boolean {
        return bypass[AudioProcessorType.CapturePost] ?: false
    }

    fun getRenderPreProcessor(): AudioProcessorInterface? {
        return process[AudioProcessorType.RenderPre]
    }

    fun getRenderPreBypass(): Boolean {
        return bypass[AudioProcessorType.RenderPre] ?: false
    }

    constructor(
        capturePostProcessor: AudioProcessorInterface? = null,
        capturePostBypass: Boolean = false,
        renderPreProcessor: AudioProcessorInterface? = null,
        renderPreBypass: Boolean = false,
    ) {
        if (capturePostProcessor != null) {
            process = process.plus(Pair(AudioProcessorType.CapturePost, capturePostProcessor))
            bypass = bypass.plus(Pair(AudioProcessorType.CapturePost, capturePostBypass))
        }
        if (renderPreProcessor != null) {
            process = process.plus(Pair(AudioProcessorType.RenderPre, renderPreProcessor))
            bypass = bypass.plus(Pair(AudioProcessorType.RenderPre, renderPreBypass))
        }
    }
}
