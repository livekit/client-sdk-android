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

import android.content.Context
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame

/**
 * A [VideoCapturer] that can be manually driven by passing in [VideoFrame] to [pushVideoFrame].
 *
 * Once [startCapture] is called, call [pushVideoFrame] to publish video frames.
 */
open class VideoFrameCapturer : VideoCapturer {

    var capturerObserver: CapturerObserver? = null

    // This is automatically called when creating the LocalVideoTrack with the capturer.
    override fun initialize(helper: SurfaceTextureHelper, context: Context?, capturerObserver: CapturerObserver) {
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        capturerObserver?.onCapturerStarted(true)
    }

    override fun stopCapture() {
        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
    }

    override fun dispose() {
    }

    override fun isScreencast(): Boolean = false

    fun pushVideoFrame(frame: VideoFrame) {
        capturerObserver?.onFrameCaptured(frame)
    }
}
