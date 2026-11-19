/*
 * Copyright 2023-2026 LiveKit, Inc.
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

import livekit.org.webrtc.FrameMetadataTransformer
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSource

class MockVideoSource(nativeSource: Long = 100) : VideoSource(nativeSource) {

    /** The send transformers currently linked to this source. */
    val frameMetadataTransformers = mutableListOf<FrameMetadataTransformer>()

    var videoProcessor: VideoProcessor? = null
        private set

    override fun dispose() {
    }

    override fun addFrameMetadataTransformer(transformer: FrameMetadataTransformer) {
        frameMetadataTransformers.add(transformer)
    }

    override fun removeFrameMetadataTransformer(transformer: FrameMetadataTransformer) {
        frameMetadataTransformers.remove(transformer)
    }

    override fun setVideoProcessor(newVideoProcessor: VideoProcessor?) {
        videoProcessor = newVideoProcessor
        super.setVideoProcessor(newVideoProcessor)
    }
}
