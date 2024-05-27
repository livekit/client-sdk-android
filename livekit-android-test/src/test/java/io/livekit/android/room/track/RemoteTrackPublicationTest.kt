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

import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockMediaStream
import io.livekit.android.test.mock.MockRtpReceiver
import io.livekit.android.test.mock.MockVideoStreamTrack
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.createMediaStreamId
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.LivekitModels.VideoQuality
import livekit.LivekitRtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RemoteTrackPublicationTest : MockE2ETest() {

    @Test
    fun trackSetting() = runTest {
        room.adaptiveStream = false

        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        room.onAddTrack(
            MockRtpReceiver.create(),
            MockVideoStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_VIDEO_TRACK.sid,
                    ),
                ),
            ),
        )

        advanceUntilIdle()
        wsFactory.ws.clearRequests()

        val remoteVideoPub = room.remoteParticipants.values.first()
            .videoTrackPublications.first()
            .first as RemoteTrackPublication

        remoteVideoPub.setVideoQuality(io.livekit.android.room.track.VideoQuality.LOW)
        remoteVideoPub.setVideoFps(100)

        advanceUntilIdle()

        val lastRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(wsFactory.ws.sentRequests.last().toPBByteString())
            .build()

        assertTrue(lastRequest.hasTrackSetting())
        assertEquals(100, lastRequest.trackSetting.fps)
        assertEquals(VideoQuality.LOW, lastRequest.trackSetting.quality)
    }
}
