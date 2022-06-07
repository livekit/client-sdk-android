package io.livekit.android.room.participant

import org.junit.Test

class ParticipantTrackPermissionTest {
    @Test(expected = IllegalArgumentException::class)
    fun requireSidOrIdentity() {
        ParticipantTrackPermission(
            participantIdentity = null,
            participantSid = null
        )
    }

    @Test
    fun sidConstructionDoesntThrow() {
        ParticipantTrackPermission(
            participantSid = "sid"
        )
    }

    @Test
    fun identyConstructionDoesntThrow() {
        ParticipantTrackPermission(
            participantIdentity = "identity"
        )
    }
}