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

package io.livekit.android.test.mock.room.participant

import io.livekit.android.room.RTCEngine
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.test.mock.room.track.TestRemoteAudioTrackFactory
import io.livekit.android.test.mock.room.track.TestRemoteVideoTrackFactory
import kotlinx.coroutines.CoroutineDispatcher
import livekit.LivekitModels

class TestRemoteParticipantFactory(
    val rtcEngine: RTCEngine,
    val ioDispatcher: CoroutineDispatcher,
    val defaultDispatcher: CoroutineDispatcher,
    val audioTrackFactory: RemoteAudioTrack.Factory = TestRemoteAudioTrackFactory,
    val videoTrackFactory: RemoteVideoTrack.Factory = TestRemoteVideoTrackFactory(defaultDispatcher),
) : RemoteParticipant.Factory {
    override fun create(info: LivekitModels.ParticipantInfo): RemoteParticipant {
        return RemoteParticipant(
            info = info,
            signalClient = rtcEngine.client,
            ioDispatcher = ioDispatcher,
            defaultDispatcher = defaultDispatcher,
            audioTrackFactory = audioTrackFactory,
            videoTrackFactory = videoTrackFactory,
        )
    }
}
