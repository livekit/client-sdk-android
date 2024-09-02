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

package io.livekit.android.room.track

import io.livekit.android.webrtc.peerconnection.executeBlockingOnRTCThread
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoTrack

abstract class VideoTrack(name: String, override val rtcTrack: VideoTrack) :
    Track(name, Kind.VIDEO, rtcTrack) {
    protected val sinks: MutableList<VideoSink> = ArrayList()

    /**
     * Add a [VideoSink] that will receive frames.
     */
    open fun addRenderer(renderer: VideoSink) {
        withRTCTrack {
            sinks.add(renderer)
            rtcTrack.addSink(renderer)
        }
    }

    /**
     * Remove a previously added [VideoSink].
     */
    open fun removeRenderer(renderer: VideoSink) {
        executeBlockingOnRTCThread {
            if (!isDisposed) {
                rtcTrack.removeSink(renderer)
            }
            sinks.remove(renderer)
        }
    }

    override fun stop() {
        executeBlockingOnRTCThread {
            if (!isDisposed) {
                for (sink in sinks) {
                    rtcTrack.removeSink(sink)
                }
            }
            sinks.clear()
        }
        super.stop()
    }
}
