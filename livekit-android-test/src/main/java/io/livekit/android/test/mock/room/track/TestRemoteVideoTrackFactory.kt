/*
 * Copyright 2025 LiveKit, Inc.
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

package io.livekit.android.test.mock.room.track

import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.test.mock.MockRTCThreadToken
import kotlinx.coroutines.CoroutineDispatcher
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.VideoTrack

class TestRemoteVideoTrackFactory(val dispatcher: CoroutineDispatcher) : RemoteVideoTrack.Factory {
    override fun create(
        name: String,
        rtcTrack: VideoTrack,
        autoManageVideo: Boolean,
        receiver: RtpReceiver,
    ): RemoteVideoTrack {
        return RemoteVideoTrack(
            name = name,
            rtcTrack = rtcTrack,
            autoManageVideo = autoManageVideo,
            dispatcher = dispatcher,
            receiver = receiver,
            rtcThreadToken = MockRTCThreadToken(),
        )
    }
}
