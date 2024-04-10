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
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.RtpReceiver

class RemoteAudioTrack(
    name: String,
    rtcTrack: AudioTrack,
    internal val receiver: RtpReceiver,
) : io.livekit.android.room.track.AudioTrack(name, rtcTrack) {

    /**
     * Adds a sink that receives the audio bytes and related information
     * for this audio track. Repeated calls using the same sink will
     * only add the sink once.
     *
     * Implementations should copy the audio data into a local copy if they wish
     * to use the data after this function returns.
     */
    fun addSink(sink: AudioTrackSink) {
        executeBlockingOnRTCThread {
            rtcTrack.addSink(sink)
        }
    }

    /**
     * Removes a previously added sink.
     */
    fun removeSink(sink: AudioTrackSink) {
        executeBlockingOnRTCThread {
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
        executeBlockingOnRTCThread {
            rtcTrack.setVolume(volume)
        }
    }
}
