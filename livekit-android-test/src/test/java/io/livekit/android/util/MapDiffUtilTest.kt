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

package io.livekit.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MapDiffUtilTest {

    @Test
    fun newMapChangesValues() {
        val oldMap = mapOf("a" to "1", "b" to "2", "c" to "3")
        val newMap = mapOf("a" to "1", "b" to "0", "c" to "3")

        val diff = diffMapChange(newMap, oldMap, "").entries
        assertEquals(1, diff.size)
        val entry = diff.first()
        assertEquals("b", entry.key)
        assertEquals("0", entry.value)
    }

    @Test
    fun newMapAddsValues() {
        val oldMap = mapOf("a" to "1", "b" to "2", "c" to "3")
        val newMap = mapOf("a" to "1", "b" to "2", "c" to "3", "d" to "4")

        val diff = diffMapChange(newMap, oldMap, "").entries
        assertEquals(1, diff.size)
        val entry = diff.first()
        assertEquals("d", entry.key)
        assertEquals("4", entry.value)
    }

    @Test
    fun newMapDeletesValues() {
        val oldMap = mapOf("a" to "1", "b" to "2", "c" to "3")
        val newMap = mapOf("a" to "1", "b" to "2")

        val diff = diffMapChange(newMap, oldMap, "").entries
        assertEquals(1, diff.size)
        val entry = diff.first()
        assertEquals("c", entry.key)
        assertEquals("", entry.value)
    }
}
