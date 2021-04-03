package io.livekit.android.room.participant

import livekit.LivekitModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParticipantTest {

    lateinit var participant: Participant

    @Before
    fun setup() {
        participant = Participant("", null)
    }

    @Test
    fun updateFromInfo() {
        participant.updateFromInfo(INFO)

        assertTrue(participant.hasInfo)
        assertEquals(INFO.sid, participant.sid)
        assertEquals(INFO.identity, participant.identity)
        assertEquals(INFO.metadata, participant.metadata)
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

    companion object {
        val INFO = LivekitModels.ParticipantInfo.newBuilder()
            .setSid("sid")
            .setIdentity("identity")
            .setMetadata("metadata")
            .build()
    }
}