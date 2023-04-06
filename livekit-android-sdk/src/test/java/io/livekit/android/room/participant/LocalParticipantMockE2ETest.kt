package io.livekit.android.room.participant

import io.livekit.android.MockE2ETest
import io.livekit.android.assert.assertIsClassList
import io.livekit.android.events.EventCollector
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.mock.MockAudioStreamTrack
import io.livekit.android.room.SignalClientTest
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.util.toOkioByteString
import io.livekit.android.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import livekit.LivekitRtc
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class LocalParticipantMockE2ETest : MockE2ETest() {

    @Test
    fun disconnectCleansLocalParticipant() = runTest {
        connect()

        val publishJob = launch {
            room.localParticipant.publishAudioTrack(
                LocalAudioTrack(
                    "",
                    MockAudioStreamTrack(id = SignalClientTest.LOCAL_TRACK_PUBLISHED.trackPublished.cid)
                )
            )
        }
        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.LOCAL_TRACK_PUBLISHED.toOkioByteString())
        publishJob.join()

        room.disconnect()

        assertEquals("", room.localParticipant.sid)
        assertNull(room.localParticipant.name)
        assertNull(room.localParticipant.identity)
        assertNull(room.localParticipant.metadata)
        assertNull(room.localParticipant.permissions)
        assertNull(room.localParticipant.participantInfo)
        assertFalse(room.localParticipant.isSpeaking)
        assertEquals(ConnectionQuality.UNKNOWN, room.localParticipant.connectionQuality)

        assertEquals(0, room.localParticipant.tracks.values.size)
        assertEquals(0, room.localParticipant.audioTracks.size)
        assertEquals(0, room.localParticipant.videoTracks.size)
    }

    @Test
    fun updateName() = runTest {
        connect()
        val newName = "new_name"
        wsFactory.ws.clearRequests()

        room.localParticipant.updateName(newName)

        val requestString = wsFactory.ws.sentRequests.first().toPBByteString()
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()

        assertTrue(sentRequest.hasUpdateMetadata())
        assertEquals(newName, sentRequest.updateMetadata.name)
    }

    @Test
    fun updateMetadata() = runTest {
        connect()
        val newMetadata = "new_metadata"
        wsFactory.ws.clearRequests()

        room.localParticipant.updateMetadata(newMetadata)

        val requestString = wsFactory.ws.sentRequests.first().toPBByteString()
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()

        assertTrue(sentRequest.hasUpdateMetadata())
        assertEquals(newMetadata, sentRequest.updateMetadata.metadata)
    }

    @Test
    fun participantMetadataChanged() = runTest {
        connect()

        val roomEventsCollector = EventCollector(room.events, coroutineRule.scope)
        val participantEventsCollector = EventCollector(room.localParticipant.events, coroutineRule.scope)

        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.LOCAL_PARTICIPANT_METADATA_CHANGED.toOkioByteString()
        )

        val roomEvents = roomEventsCollector.stopCollecting()
        val participantEvents = participantEventsCollector.stopCollecting()

        val localParticipant = room.localParticipant
        val updateData = SignalClientTest.REMOTE_PARTICIPANT_METADATA_CHANGED.update.getParticipants(0)
        assertEquals(updateData.metadata, localParticipant.metadata)
        assertEquals(updateData.name, localParticipant.name)

        assertIsClassList(
            listOf(
                RoomEvent.ParticipantMetadataChanged::class.java,
                RoomEvent.ParticipantNameChanged::class.java,
            ),
            roomEvents
        )

        assertIsClassList(
            listOf(
                ParticipantEvent.MetadataChanged::class.java,
                ParticipantEvent.NameChanged::class.java,
            ),
            participantEvents
        )
    }
}