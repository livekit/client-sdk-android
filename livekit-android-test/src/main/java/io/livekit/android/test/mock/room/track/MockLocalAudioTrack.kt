/*
 * Copyright 2024-2025 LiveKit, Inc.
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

import io.livekit.android.audio.AudioBufferCallbackDispatcher
import io.livekit.android.audio.AudioProcessingController
import io.livekit.android.audio.AudioRecordPrewarmer
import io.livekit.android.audio.AudioRecordSamplesDispatcher
import io.livekit.android.audio.NoAudioRecordPrewarmer
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockAudioProcessingController
import io.livekit.android.test.mock.MockAudioStreamTrack
import io.livekit.android.test.mock.TestData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.org.webrtc.AudioTrack

@OptIn(ExperimentalCoroutinesApi::class)
fun MockE2ETest.createMockLocalAudioTrack(
    name: String = "",
    mediaTrack: AudioTrack = MockAudioStreamTrack(id = TestData.LOCAL_TRACK_PUBLISHED.trackPublished.cid),
    options: LocalAudioTrackOptions = LocalAudioTrackOptions(),
    audioProcessingController: AudioProcessingController = MockAudioProcessingController(),
    dispatcher: CoroutineDispatcher = coroutineRule.dispatcher,
    audioRecordSamplesDispatcher: AudioRecordSamplesDispatcher = AudioRecordSamplesDispatcher(),
    audioBufferCallbackDispatcher: AudioBufferCallbackDispatcher = AudioBufferCallbackDispatcher(),
    audioRecordPrewarmer: AudioRecordPrewarmer = NoAudioRecordPrewarmer(),
): LocalAudioTrack {
    return LocalAudioTrack(
        name = name,
        mediaTrack = mediaTrack,
        options = options,
        audioProcessingController = audioProcessingController,
        dispatcher = dispatcher,
        audioRecordSamplesDispatcher = audioRecordSamplesDispatcher,
        audioBufferCallbackDispatcher = audioBufferCallbackDispatcher,
        audioRecordPrewarmer = audioRecordPrewarmer,
    )
}
