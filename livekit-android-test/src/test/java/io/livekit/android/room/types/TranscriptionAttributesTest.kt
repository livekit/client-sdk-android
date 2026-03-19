/*
 * Copyright 2026 LiveKit, Inc.
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
