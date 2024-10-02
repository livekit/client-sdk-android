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

import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.RtpReceiver

/**
 * A representation of a remote audio track.
 */
class RemoteAudioTrack(
    name: String,
    rtcTrack: AudioTrack,
    internal val receiver: RtpReceiver,
) : io.livekit.android.room.track.AudioTrack(name, rtcTrack) {

    override fun addSink(sink: AudioTrackSink) {
        withRTCTrack {
            rtcTrack.addSink(sink)
        }
    }

    override fun removeSink(sink: AudioTrackSink) {
        withRTCTrack {
            rtcTrack.removeSink(sink)
        }
    }

    /**
     * Sets the volume.
     *
     * @param volume a gain value in the range 0 to 10.
     * * 0 will mute the track
     * * 1 will play the track normally
     * * values greater than 1 will boost the volume of the track.
     */
    fun setVolume(volume: Double) {
        withRTCTrack {
            rtcTrack.setVolume(volume)
        }
    }
}
