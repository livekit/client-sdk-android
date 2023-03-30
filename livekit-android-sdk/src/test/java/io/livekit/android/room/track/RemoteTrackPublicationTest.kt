package io.livekit.android.room.track

import io.livekit.android.MockE2ETest
import io.livekit.android.mock.*
import io.livekit.android.room.SignalClientTest
import io.livekit.android.util.toOkioByteString
import io.livekit.android.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.LivekitModels
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
            SignalClientTest.PARTICIPANT_JOIN.toOkioByteString()
        )

        room.onAddTrack(
            MockVideoStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_VIDEO_TRACK.sid
                    )
                )
            )
        )

        advanceUntilIdle()
        wsFactory.ws.clearRequests()

        val remoteVideoPub = room.remoteParticipants.values.first()
            .videoTracks.first()
            .first as RemoteTrackPublication

        remoteVideoPub.setVideoQuality(VideoQuality.LOW)
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