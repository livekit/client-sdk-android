/*
 * Copyright 2023-2026 LiveKit, Inc.
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
import org.junit.Assert.assertFalse
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

    private suspend fun subscribeRemoteVideo(): RemoteTrackPublication {
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

        return room.remoteParticipants.values.first()
            .videoTrackPublications.first()
            .first as RemoteTrackPublication
    }

    private fun lastTrackSetting(): LivekitRtc.UpdateTrackSettings {
        val requests = wsFactory.ws.sentRequests.map {
            LivekitRtc.SignalRequest.newBuilder().mergeFrom(it.toPBByteString()).build()
        }
        val lastTrackSetting = requests.lastOrNull { it.hasTrackSetting() }
        assertTrue("no track setting sent", lastTrackSetting != null)
        return lastTrackSetting!!.trackSetting
    }

    @Test
    fun fecProtectionOmittedUntilRequested() = runTest {
        room.adaptiveStream = false
        val remoteVideoPub = subscribeRemoteVideo()
        advanceUntilIdle()
        wsFactory.ws.clearRequests()

        remoteVideoPub.setVideoFps(30)
        advanceUntilIdle()

        assertFalse(lastTrackSetting().hasFec())
        assertEquals(FecProtection.Disabled, remoteVideoPub.fecProtection)
    }

    @Test
    fun fecProtectionSentAndKept() = runTest {
        room.adaptiveStream = false
        val remoteVideoPub = subscribeRemoteVideo()
        advanceUntilIdle()
        wsFactory.ws.clearRequests()

        remoteVideoPub.setFecProtection(FecProtection.Fixed(FecProtectionLevel.HIGH))
        advanceUntilIdle()

        assertEquals(FecProtection.Fixed(FecProtectionLevel.HIGH), remoteVideoPub.fecProtection)
        assertTrue(lastTrackSetting().hasFec())
        assertEquals(LivekitRtc.FECProtectionMode.FEC_MODE_FIXED, lastTrackSetting().fec.mode)
        assertEquals(LivekitRtc.FECProtectionLevel.FEC_LEVEL_HIGH, lastTrackSetting().fec.level)

        // later settings updates keep the requested level
        remoteVideoPub.setVideoFps(30)
        advanceUntilIdle()
        assertEquals(30, lastTrackSetting().fps)
        assertEquals(LivekitRtc.FECProtectionMode.FEC_MODE_FIXED, lastTrackSetting().fec.mode)
        assertEquals(LivekitRtc.FECProtectionLevel.FEC_LEVEL_HIGH, lastTrackSetting().fec.level)

        remoteVideoPub.setFecProtection(FecProtection.Adaptive)
        advanceUntilIdle()
        assertEquals(LivekitRtc.FECProtectionMode.FEC_MODE_ADAPTIVE, lastTrackSetting().fec.mode)
        assertEquals(LivekitRtc.FECProtectionLevel.FEC_LEVEL_UNSPECIFIED, lastTrackSetting().fec.level)

        // disabling is sent explicitly rather than omitted
        remoteVideoPub.setFecProtection(FecProtection.Disabled)
        advanceUntilIdle()
        assertTrue(lastTrackSetting().hasFec())
        assertEquals(LivekitRtc.FECProtectionMode.FEC_MODE_DISABLED, lastTrackSetting().fec.mode)
    }

    @Test
    fun fecProtectionWithAdaptiveStream() = runTest {
        room.adaptiveStream = true
        val remoteVideoPub = subscribeRemoteVideo()
        advanceUntilIdle()
        wsFactory.ws.clearRequests()

        // applies even when adaptive stream manages the track's quality
        remoteVideoPub.setFecProtection(FecProtection.Minimum(FecProtectionLevel.MEDIUM))
        advanceUntilIdle()

        assertEquals(LivekitRtc.FECProtectionMode.FEC_MODE_MINIMUM, lastTrackSetting().fec.mode)
        assertEquals(LivekitRtc.FECProtectionLevel.FEC_LEVEL_MEDIUM, lastTrackSetting().fec.level)
    }

    @Test
    fun subscriptionStatus() = runTest {
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

        assertEquals(RemoteTrackPublication.SubscriptionStatus.SUBSCRIBED, remoteVideoPub.subscriptionStatus)

        remoteVideoPub.setSubscribed(false)

        assertEquals(RemoteTrackPublication.SubscriptionStatus.UNSUBSCRIBED, remoteVideoPub.subscriptionStatus)

        val lastRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(wsFactory.ws.sentRequests.last().toPBByteString())
            .build()

        assertTrue(lastRequest.hasSubscription())
        assertFalse(lastRequest.subscription.subscribe)
        val trackList = lastRequest.subscription.trackSidsList
        assertEquals(1, trackList.size)
        assertEquals(TestData.REMOTE_VIDEO_TRACK.sid, trackList.first())
    }
}
