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

/**
 * A class representing an audio track.
 */
abstract class AudioTrack(
    name: String,
    /**
     * The underlying WebRTC audio track.
     */
    override val rtcTrack: AudioTrack,
) : Track(name, Kind.AUDIO, rtcTrack) {

    /**
     * Adds a sink that receives the audio bytes and related information
     * for this audio track. Repeated calls using the same sink will
     * only add the sink once.
     *
     * Implementations should copy the audio data into a local copy if they wish
     * to use the data after the [AudioTrackSink.onData] callback returns.
     * Long running processing of the received audio data should be done in a separate
     * thread, as doing so inline may block the audio thread.
     */
    abstract fun addSink(sink: AudioTrackSink)

    /**
     * Removes a previously added sink.
     */
    abstract fun removeSink(sink: AudioTrackSink)
}
