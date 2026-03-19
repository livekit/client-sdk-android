package io.livekit.android.room.types

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionAttributesTest {

    @Test
    fun simpleConversion() {
        val attributes = mapOf(
            "lk.transcribed_track_id" to "track_id",
            "lk.transcription_final" to "false",
            "lk.segment_id" to "segment_id",
        )

        val transcriptionAttributes = TranscriptionAttributes.fromStringMap(attributes)

        assertEquals("track_id", transcriptionAttributes.lkTranscribedTrackID)
        assertEquals(false, transcriptionAttributes.lkTranscriptionFinal)
        assertEquals("segment_id", transcriptionAttributes.lkSegmentID)
    }
}
