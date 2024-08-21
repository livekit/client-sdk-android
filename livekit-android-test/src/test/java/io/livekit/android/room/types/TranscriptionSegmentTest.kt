/*
 * Copyright 2024 LiveKit, Inc.
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

class TranscriptionSegmentTest {

    @Test
    fun mergeSegments() {
        val first = TranscriptionSegment(
            id = "1",
            text = "text",
            language = "language",
            final = false,
            firstReceivedTime = 0,
            lastReceivedTime = 0,
        )

        val last = TranscriptionSegment(
            id = "1",
            text = "newtext",
            language = "newlanguage",
            final = true,
            firstReceivedTime = 100,
            lastReceivedTime = 100,
        )

        val merged = first.merge(last)

        val expected = TranscriptionSegment(
            id = "1",
            text = "newtext",
            language = "newlanguage",
            final = true,
            firstReceivedTime = 0,
            lastReceivedTime = 100,
        )
        assertEquals(expected, merged)
    }
}
