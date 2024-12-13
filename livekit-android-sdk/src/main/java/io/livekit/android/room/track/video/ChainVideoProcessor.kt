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

import androidx.annotation.CallSuper
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink

/**
 * A VideoProcessor that can be chained together.
 *
 * Child classes should propagate frames down to the
 * next link through [continueChain].
 */
abstract class ChainVideoProcessor : VideoProcessor {

    /**
     * The video sink where frames that have been completely processed are sent.
     */
    var videoSink: VideoSink? = null
        private set

    /**
     * The next link in the chain to feed frames to.
     *
     * Setting [childVideoProcessor] to null will mean that this is object
     * the end of the chain, and processed frames are ready to be published.
     */
    var childVideoProcessor: VideoProcessor? = null
        set(value) {
            value?.setSink(videoSink)
            field = value
        }

    @CallSuper
    override fun onCapturerStarted(started: Boolean) {
        childVideoProcessor?.onCapturerStarted(started)
    }

    @CallSuper
    override fun onCapturerStopped() {
        childVideoProcessor?.onCapturerStopped()
    }

    final override fun setSink(videoSink: VideoSink?) {
        childVideoProcessor?.setSink(videoSink)
        this.videoSink = videoSink
    }

    /**
     * A utility method to pass the frame down to the next link in the chain.
     */
    protected fun continueChain(frame: VideoFrame) {
        childVideoProcessor?.onFrameCaptured(frame) ?: videoSink?.onFrame(frame)
    }
}
