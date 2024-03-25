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

package io.livekit.android.test.mock

import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.VideoTrack

fun createMediaStreamId(participantSid: String, trackSid: String) =
    "$participantSid|$trackSid"

class MockMediaStream(private val id: String = "id") : MediaStream(1L) {

    override fun addTrack(track: AudioTrack): Boolean {
        return audioTracks.add(track)
    }

    override fun addTrack(track: VideoTrack?): Boolean {
        return videoTracks.add(track)
    }

    override fun addPreservedTrack(track: VideoTrack?): Boolean {
        return preservedVideoTracks.add(track)
    }

    override fun removeTrack(track: AudioTrack?): Boolean {
        return audioTracks.remove(track)
    }

    override fun removeTrack(track: VideoTrack?): Boolean {
        return videoTracks.remove(track)
    }

    override fun dispose() {
        // Don't do anything in this stubbed class
    }

    override fun getId(): String = id
}
