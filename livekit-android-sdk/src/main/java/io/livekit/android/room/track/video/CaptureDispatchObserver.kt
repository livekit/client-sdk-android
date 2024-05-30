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

import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

/**
 * @suppress
 */
class CaptureDispatchObserver : CapturerObserver {
    private val observers = linkedSetOf<CapturerObserver>()
    private val sinks = linkedSetOf<VideoSink>()

    @Synchronized
    fun registerObserver(observer: CapturerObserver) {
        observers.add(observer)
    }

    @Synchronized
    fun unregisterObserver(observer: CapturerObserver) {
        observers.remove(observer)
    }

    @Synchronized
    fun registerSink(sink: VideoSink) {
        sinks.add(sink)
    }

    @Synchronized
    fun unregisterSink(sink: VideoSink) {
        sinks.remove(sink)
    }

    @Synchronized
    override fun onCapturerStarted(success: Boolean) {
        for (observer in observers) {
            observer.onCapturerStarted(success)
        }
    }

    @Synchronized
    override fun onCapturerStopped() {
        for (observer in observers) {
            observer.onCapturerStopped()
        }
    }

    @Synchronized
    override fun onFrameCaptured(frame: VideoFrame) {
        for (observer in observers) {
            observer.onFrameCaptured(frame)
        }

        for (sink in sinks) {
            sink.onFrame(frame)
        }
    }
}
