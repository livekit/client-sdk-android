package io.livekit.android.mock

import io.livekit.android.audio.AudioProcessingController
import io.livekit.android.audio.AudioProcessorInterface

class MockAudioProcessingController : AudioProcessingController {
    override fun setCapturePostProcessing(processing: AudioProcessorInterface?) {
    }

    override fun setBypassForCapturePostProcessing(bypass: Boolean) {
    }

    override fun setRenderPreProcessing(processing: AudioProcessorInterface?) {
    }

    override fun setBypassForRenderPreProcessing(bypass: Boolean) {
    }
}
