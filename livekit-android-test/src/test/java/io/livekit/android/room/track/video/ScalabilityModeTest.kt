/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android.room.track.video

import org.junit.Assert.assertEquals
import org.junit.Test

class ScalabilityModeTest {

    @Test
    fun testL1T3() {
        val mode = ScalabilityMode.parseFromString("L1T3")

        assertEquals(1, mode.spatial)
        assertEquals(3, mode.temporal)
        assertEquals("", mode.suffix)
    }

    @Test
    fun testL3T3_KEY() {
        val mode = ScalabilityMode.parseFromString("L3T3_KEY")

        assertEquals(3, mode.spatial)
        assertEquals(3, mode.temporal)
        assertEquals("_KEY", mode.suffix)
    }
}
