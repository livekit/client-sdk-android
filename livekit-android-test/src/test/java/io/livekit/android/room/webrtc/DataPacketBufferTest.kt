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

package io.livekit.android.room.webrtc

import io.livekit.android.test.BaseTest
import io.livekit.android.webrtc.DataPacketBuffer
import io.livekit.android.webrtc.DataPacketItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class DataPacketBufferTest : BaseTest() {

    lateinit var buffer: DataPacketBuffer

    @Before
    fun setup() {
        buffer = DataPacketBuffer(5)
    }

    fun fillWithTestValues() {
        for (i in 1..5) {
            val bytes = ByteArray(i)
            buffer.queue(DataPacketItem(ByteBuffer.wrap(bytes), i))
        }
    }

    @Test
    fun queue() {
        fillWithTestValues()
        assertEquals(15, buffer.byteSize())
        assertEquals(5, buffer.size())
    }

    @Test
    fun dequeue() {
        fillWithTestValues()
        val list = mutableListOf<DataPacketItem>()
        for (i in 1..5) {
            val item = buffer.dequeue()
            assertNotNull(item)

            list.add(item!!)
        }

        list.forEachIndexed { index, item ->
            assertEquals(index + 1, item.sequence)
            assertEquals(index + 1, item.data.capacity())
        }
    }

    @Test
    fun clear() {
        fillWithTestValues()
        buffer.clear()
        assertEquals(0, buffer.byteSize())
        assertEquals(0, buffer.size())
    }

    @Test
    fun getAll() {
        fillWithTestValues()
        val list = buffer.getAll()

        assertEquals(5, list.size)
        list.forEachIndexed { index, item ->
            assertEquals(index + 1, item.sequence)
            assertEquals(index + 1, item.data.capacity())
        }
    }

    @Test
    fun trim() {
        fillWithTestValues()
        buffer.trim(9)
        // extra capacity is set to 5, so only the 1st packet is dropped.
        assertEquals(14, buffer.byteSize())
        assertEquals(4, buffer.size())
    }

    @Test
    fun popToSequence() {
        fillWithTestValues()
        buffer.popToSequence(3)

        assertEquals(9, buffer.byteSize())
        assertEquals(2, buffer.size())
    }
}
