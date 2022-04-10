package io.livekit.android.room.participant

import io.livekit.android.MockE2ETest
import io.livekit.android.events.EventCollector
import io.livekit.android.events.RoomEvent
import io.livekit.android.mock.MockAudioStreamTrack
import io.livekit.android.room.SignalClientTest
import io.livekit.android.room.track.LocalAudioTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner


@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class ParticipantMockE2ETest : MockE2ETest() {

    @Test
    fun trackUnpublished() = runTest {
        connect()

        // publish track
        val publishJob = launch {
            room.localParticipant.publishAudioTrack(
                LocalAudioTrack(
                    "",
                    MockAudioStreamTrack(id = SignalClientTest.LOCAL_TRACK_PUBLISHED.trackPublished.cid)
                )
            )
        }
        simulateMessageFromServer(SignalClientTest.LOCAL_TRACK_PUBLISHED)
        publishJob.join()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        // remote unpublish
        simulateMessageFromServer(SignalClientTest.LOCAL_TRACK_UNPUBLISHED)
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.TrackUnpublished)
        Assert.assertEquals(0, room.localParticipant.tracks.size)
    }

    @Test
    fun participantPermissions() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        simulateMessageFromServer(SignalClientTest.PERMISSION_CHANGE)
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(1, events.size)
        Assert.assertEquals(true, events[0] is RoomEvent.ParticipantPermissionsChanged)
    }
}