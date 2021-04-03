package io.livekit.android.room.participant

import io.livekit.android.room.RTCClient
import livekit.LivekitModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class RemoteParticipantTest {

    lateinit var participant: RemoteParticipant

    @Before
    fun setup() {
        val rtcClient = Mockito.mock(RTCClient::class.java)
        participant = RemoteParticipant(rtcClient, "sid")
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
