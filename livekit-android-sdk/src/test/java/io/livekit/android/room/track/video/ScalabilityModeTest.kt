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
