package io.livekit.android.room.participant

import io.livekit.android.coroutines.TestCoroutineRule
import io.livekit.android.events.EventCollector
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.room.track.TrackPublication
import livekit.LivekitModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ParticipantTest {

    @get:Rule
    var coroutineRule = TestCoroutineRule()

    lateinit var participant: Participant

    @Before
    fun setup() {
        participant = Participant("", null, coroutineRule.dispatcher)
    }

    @Test
    fun updateFromInfo() {
        participant.updateFromInfo(INFO)

        assertTrue(participant.hasInfo)
        assertEquals(INFO.sid, participant.sid)
        assertEquals(INFO.identity, participant.identity)
        assertEquals(INFO.metadata, participant.metadata)
        assertEquals(INFO, participant.participantInfo)
    }

    @Test
    fun setMetadataCallsListeners() {
        class MetadataListener : ParticipantListener {
            var wasCalled = false
            lateinit var participantValue: Participant
            var prevMetadataValue: String? = null
            override fun onMetadataChanged(participant: Participant, prevMetadata: String?) {
                wasCalled = true
                participantValue = participant
                prevMetadataValue = prevMetadata
            }
        }

        val publicListener = MetadataListener()
        val internalListener = MetadataListener()

        participant.listener = publicListener
        participant.internalListener = internalListener

        val prevMetadata = participant.metadata
        val metadata = "metadata"
        participant.metadata = metadata

        fun checkValues(listener: MetadataListener) {
            assertTrue(listener.wasCalled)
            assertEquals(participant, listener.participantValue)
            assertEquals(prevMetadata, listener.prevMetadataValue)
        }

        checkValues(publicListener)
        checkValues(internalListener)
    }

    @Test
    fun setMetadataChangedEvent() {
        val eventCollector = EventCollector(participant.events, coroutineRule.scope)
        val prevMetadata = participant.metadata
        val metadata = "metadata"
        participant.metadata = metadata

        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is ParticipantEvent.MetadataChanged)

        val event = events[0] as ParticipantEvent.MetadataChanged

        assertEquals(prevMetadata, event.prevMetadata)
        assertEquals(participant, event.participant)
    }

    @Test
    fun setIsSpeakingChangedEvent() {
        val eventCollector = EventCollector(participant.events, coroutineRule.scope)
        val newIsSpeaking = !participant.isSpeaking
        participant.isSpeaking = newIsSpeaking

        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is ParticipantEvent.SpeakingChanged)

        val event = events[0] as ParticipantEvent.SpeakingChanged

        assertEquals(participant, event.participant)
        assertEquals(newIsSpeaking, event.isSpeaking)
    }

    @Test
    fun addTrackPublication() {
        val audioPublication = TrackPublication(TRACK_INFO, null, participant)
        participant.addTrackPublication(audioPublication)

        assertEquals(1, participant.tracks.values.size)
        assertEquals(audioPublication, participant.tracks.values.first())
        assertEquals(1, participant.audioTracks.values.size)
        assertEquals(audioPublication, participant.audioTracks.values.first())
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