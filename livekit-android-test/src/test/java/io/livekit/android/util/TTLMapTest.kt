/*
 * Copyright 2025 LiveKit, Inc.
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

import io.livekit.android.test.BaseTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class TTLMapTest : BaseTest() {
    lateinit var map: TTLMap<String, String>
    var time = 0L

    @Before
    fun setup() {
        map = TTLMap(TTL, { time })
        time = 0L
    }

    fun expire() {
        time += 1 + TTL.inWholeMilliseconds
    }

    @Test
    fun getNormal() {
        map[KEY] = VALUE
        assertEquals(map[KEY], VALUE)
    }

    @Test
    fun getExpired() {
        map[KEY] = VALUE
        expire()
        assertNull("map key was not deleted!", map[KEY])
    }

    @Test
    fun isEmptyExpired() {
        map[KEY] = VALUE
        expire()
        assertTrue(map.isEmpty())
    }

    @Test
    fun containsKeyNormal() {
        map[KEY] = VALUE
        assertTrue(map.containsKey(KEY))
    }

    @Test
    fun containsKeyExpired() {
        map[KEY] = VALUE
        expire()
        assertFalse(map.containsKey(KEY))
    }

    @Test
    fun containsValueNormal() {
        map[KEY] = VALUE
        assertTrue(map.containsValue(VALUE))
    }

    @Test
    fun containsValueExpired() {
        map[KEY] = VALUE
        expire()
        assertFalse(map.containsValue(VALUE))
    }

    @Test
    fun clear() {
        map[KEY] = VALUE
        map.clear()
        assertTrue(map.isEmpty())
    }

    @Test
    fun remove() {
        map[KEY] = VALUE
        map.remove(KEY)
        assertFalse(map.containsKey(KEY))
    }

    @Test
    fun cleanup() {
        map[KEY] = VALUE
        map.cleanup()
        assertEquals(map[KEY], VALUE)
    }

    @Test
    fun cleanupExpired() {
        map[KEY] = VALUE
        expire()
        map.cleanup()
        assertTrue(map.isEmpty())
    }

    @Test
    fun sizeNormal() {
        map[KEY] = VALUE
        assertEquals(1, map.size)
    }

    @Test
    fun sizeExpired() {
        map[KEY] = VALUE
        expire()
        assertEquals(0, map.size)
    }

    companion object {
        val TTL = 1000.milliseconds

        val KEY = "hello"
        val VALUE = "world"
    }
}
