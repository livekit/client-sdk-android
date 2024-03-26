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

import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoTrack
import java.util.UUID

class MockVideoStreamTrack(
    val id: String = UUID.randomUUID().toString(),
    val kind: String = VIDEO_TRACK_KIND,
    var enabled: Boolean = true,
    var state: State = State.LIVE,
) : VideoTrack(1L) {
    val sinks = mutableSetOf<VideoSink>()

    private var shouldReceive = true

    override fun id(): String = id

    override fun kind(): String = kind

    override fun enabled(): Boolean = enabled

    override fun setEnabled(enable: Boolean): Boolean {
        enabled = enable
        return true
    }

    override fun shouldReceive() = shouldReceive

    override fun setShouldReceive(shouldReceive: Boolean) {
        this.shouldReceive = shouldReceive
    }

    override fun state(): State {
        return state
    }

    override fun dispose() {
    }

    override fun addSink(sink: VideoSink) {
        sinks.add(sink)
    }

    override fun removeSink(sink: VideoSink) {
        sinks.remove(sink)
    }
}
