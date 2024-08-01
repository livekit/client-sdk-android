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
