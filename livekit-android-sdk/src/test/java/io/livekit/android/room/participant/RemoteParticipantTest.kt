package io.livekit.android.room.participant

import io.livekit.android.BaseTest
import io.livekit.android.room.SignalClient
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import livekit.LivekitModels
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteParticipantTest : BaseTest() {


    lateinit var signalClient: SignalClient
    lateinit var participant: RemoteParticipant

    @Before
    fun setup() {
        signalClient = Mockito.mock(SignalClient::class.java)
        participant = RemoteParticipant(
            "sid",
            signalClient = signalClient,
            ioDispatcher = coroutineRule.dispatcher,
            defaultDispatcher = coroutineRule.dispatcher,
        )
    }

    @Test
    fun constructorAddsTrack() {
        val info = LivekitModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()

        participant = RemoteParticipant(
            info,
            signalClient,
            ioDispatcher = coroutineRule.dispatcher,
            defaultDispatcher = coroutineRule.dispatcher,
        )

        assertEquals(1, participant.tracks.values.size)
        assertNotNull(participant.getTrackPublication(TRACK_INFO.sid))
    }

    @Test
    fun updateFromInfoAddsTrack() {
        val newTrackInfo = LivekitModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()
        participant.updateFromInfo(newTrackInfo)

        assertEquals(1, participant.tracks.values.size)
        assertNotNull(participant.getTrackPublication(TRACK_INFO.sid))
    }

    @Test
    fun tracksFlow() = runTest {

        val newTrackInfo = LivekitModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()

        val emissions = mutableListOf<Map<String, TrackPublication>>()
        val job = launch {
            participant::tracks.flow.collect {
                emissions.add(it)
            }
        }

        assertEquals(1, emissions.size)
        assertEquals(emptyMap<String, TrackPublication>(), emissions.first())

        participant.updateFromInfo(newTrackInfo)

        job.cancel()
        assertEquals(2, emissions.size)
        assertEquals(1, emissions[1].size)
    }

    @Test
    fun audioTracksFlow() = runTest {

        val newTrackInfo = LivekitModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()

        val emissions = mutableListOf<Map<String, TrackPublication>>()
        val job = launch {
            participant::audioTracks.flow.collect {
                emissions.add(it)
            }
        }

        assertEquals(1, emissions.size)
        assertEquals(emptyMap<String, TrackPublication>(), emissions.first())

        participant.updateFromInfo(newTrackInfo)

        job.cancel()
        assertEquals(2, emissions.size)
        assertEquals(1, emissions[1].size)
    }

    @Test
    fun updateFromInfoRemovesTrack() {
        val newTrackInfo = LivekitModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()

        participant.updateFromInfo(newTrackInfo)
        participant.updateFromInfo(INFO)

        assertEquals(0, participant.tracks.values.size)
        assertNull(participant.getTrackPublication(TRACK_INFO.sid))
    }


    @Test
    fun unpublishTrackRemoves() {
        val newTrackInfo = LivekitModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()

        participant.updateFromInfo(newTrackInfo)
        participant.unpublishTrack(TRACK_INFO.sid)

        assertEquals(0, participant.tracks.values.size)
        assertNull(participant.getTrackPublication(TRACK_INFO.sid))
    }


    companion object {
        val INFO = LivekitModels.ParticipantInfo.newBuilder()
            .setSid("sid")
            .setIdentity("identity")
            .setMetadata("metadata")
            .build()

        val TRACK_INFO = LivekitModels.TrackInfo.newBuilder()
            .setSid("sid")
            .setName("name")
            .setType(LivekitModels.TrackType.AUDIO)
            .setMuted(false)
            .build()
    }
}
