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

package io.livekit.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StringByteUtilsTest {

    @Test
    fun `returns string unchanged when already within the budget`() {
        assertEquals("hello", "hello".truncateBytes(5))
        assertEquals("hello", "hello".truncateBytes(100))
        assertEquals("", "".truncateBytes(5))
    }

    @Test
    fun `truncates ascii at the byte budget`() {
        assertEquals("hel", "hello".truncateBytes(3))
    }

    @Test
    fun `keeps a multi-byte character whole when it fits`() {
        // U+1F600 encodes to 4 UTF-8 bytes.
        assertEquals("😀", "😀hello".truncateBytes(4))
    }

    @Test
    fun `does not split a surrogate pair when the budget falls mid-pair`() {
        // "a" is 1 byte, U+1F600 is 4 UTF-8 bytes / 2 UTF-16 code units.
        // A 3-byte budget cannot fit the emoji, so only "a" may survive.
        val truncated = "a😀".truncateBytes(3)

        assertEquals("a", truncated)
    }

    @Test
    fun `never emits an unpaired surrogate`() {
        // Any budget that cuts into the emoji must drop it entirely rather than
        // leaving a lone high surrogate, which does not round trip through UTF-8.
        for (maxBytes in 1..4) {
            val truncated = "a😀".truncateBytes(maxBytes)

            assertFalse(
                "truncateBytes($maxBytes) ended on a high surrogate",
                truncated.lastOrNull()?.isHighSurrogate() == true,
            )
            assertTrue(
                "truncateBytes($maxBytes) exceeded the budget",
                truncated.byteLength() <= maxBytes,
            )
            // An unpaired surrogate becomes '?' on the way out, so round tripping
            // through UTF-8 is what actually proves the result is well formed.
            assertEquals(
                "truncateBytes($maxBytes) did not round trip through UTF-8",
                truncated,
                String(truncated.toByteArray(Charsets.UTF_8), Charsets.UTF_8),
            )
        }
    }
}
