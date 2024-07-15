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

package io.livekit.android.audio

import io.livekit.android.util.FlowObservable

/**
 * Interface for controlling external audio processing.
 */
interface AudioProcessingController {
    /**
     * the audio processor to be used for capture post processing.
     */
    @FlowObservable
    @get:FlowObservable
    var capturePostProcessor: AudioProcessorInterface?

    /**
     * the audio processor to be used for render pre processing.
     */
    @FlowObservable
    @get:FlowObservable
    var renderPreProcessor: AudioProcessorInterface?

    /**
     * whether to bypass mode the render pre processing.
     */
    @FlowObservable
    @get:FlowObservable
    var bypassRenderPreProcessing: Boolean

    /**
     * whether to bypass the capture post processing.
     */
    @FlowObservable
    @get:FlowObservable
    var bypassCapturePostProcessing: Boolean

    /**
     * Set the audio processor to be used for capture post processing.
     */
    @Deprecated("Use the capturePostProcessing variable directly instead")
    fun setCapturePostProcessing(processing: AudioProcessorInterface?)

    /**
     * Set whether to bypass the capture post processing.
     */
    @Deprecated("Use the bypassCapturePostProcessing variable directly instead")
    fun setBypassForCapturePostProcessing(bypass: Boolean)

    /**
     * Set the audio processor to be used for render pre processing.
     */
    @Deprecated("Use the renderPreProcessing variable directly instead")
    fun setRenderPreProcessing(processing: AudioProcessorInterface?)

    /**
     * Set whether to bypass the render pre processing.
     */
    @Deprecated("Use the bypassRendererPreProcessing variable directly instead")
    fun setBypassForRenderPreProcessing(bypass: Boolean)
}

/**
 * @suppress
 */
interface AuthedAudioProcessingController : AudioProcessingController {
    /**
     * @suppress
     */
    fun authenticate(url: String, token: String)
}
