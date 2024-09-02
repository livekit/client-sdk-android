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

package io.livekit.android.room.track.video

import io.livekit.android.annotations.WebRTCSensitive
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor

/**
 * When not connected to a room, the base [VideoProcessor] implementation will refuse
 * to process frames as they will all be dropped (i.e. not sent).
 *
 * This implementation by default forces all frames to be processed regardless of publish status.
 *
 * Change [allowDropping] to true if you want to allow dropping of frames.
 */
abstract class NoDropVideoProcessor : VideoProcessor {
    /**
     * If set to false, forces all frames to be processed regardless of publish status.
     * If set to true, frames will only be processed when the associated video track is published.
     *
     * By default, set to false.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var allowDropping = false

    @WebRTCSensitive
    override fun onFrameCaptured(frame: VideoFrame, parameters: VideoProcessor.FrameAdaptationParameters) {
        if (allowDropping) {
            super.onFrameCaptured(frame, parameters)
        } else {
            // Altered from VideoProcessor
            val adaptedFrame = VideoProcessor.applyFrameAdaptationParameters(frame, parameters)
            if (adaptedFrame != null) {
                this.onFrameCaptured(adaptedFrame)
                adaptedFrame.release()
            } else {
                this.onFrameCaptured(frame)
            }
        }
    }
}
