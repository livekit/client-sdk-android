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

package io.livekit.android.test.mock

import io.livekit.android.audio.AudioProcessingController
import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.flowDelegate

class MockAudioProcessingController : AudioProcessingController {

    @FlowObservable
    @get:FlowObservable
    override var capturePostProcessor: AudioProcessorInterface? by flowDelegate(null)

    @FlowObservable
    @get:FlowObservable
    override var renderPreProcessor: AudioProcessorInterface? by flowDelegate(null)

    @FlowObservable
    @get:FlowObservable
    override var bypassRenderPreProcessing: Boolean by flowDelegate(false)

    @FlowObservable
    @get:FlowObservable
    override var bypassCapturePostProcessing: Boolean by flowDelegate(false)

    @Deprecated("Use the capturePostProcessing variable directly instead")
    override fun setCapturePostProcessing(processing: AudioProcessorInterface?) {
    }

    @Deprecated("Use the bypassCapturePostProcessing variable directly instead")
    override fun setBypassForCapturePostProcessing(bypass: Boolean) {
    }

    @Deprecated("Use the renderPreProcessing variable directly instead")
    override fun setRenderPreProcessing(processing: AudioProcessorInterface?) {
    }

    @Deprecated("Use the bypassRendererPreProcessing variable directly instead")
    override fun setBypassForRenderPreProcessing(bypass: Boolean) {
    }
}
