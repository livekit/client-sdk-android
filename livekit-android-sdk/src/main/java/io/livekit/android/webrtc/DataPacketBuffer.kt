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

package io.livekit.android.webrtc

import java.nio.ByteBuffer
import java.util.Deque
import java.util.LinkedList

/** @suppress */
data class DataPacketItem(val data: ByteBuffer, val sequence: Int)

/** @suppress */
class DataPacketBuffer(private val extraCapacity: Long = 0L) {
    private val buffer: Deque<DataPacketItem> = LinkedList()
    private var totalSize = 0L

    @Synchronized
    fun clear() {
        buffer.clear()
        totalSize = 0L
    }

    @Synchronized
    fun queue(item: DataPacketItem) {
        buffer.add(item)
        totalSize += item.data.capacity()
    }

    @Synchronized
    fun dequeue(): DataPacketItem? {
        if (buffer.isEmpty()) {
            return null
        }
        val item = buffer.removeFirst()
        totalSize -= item.data.capacity()
        return item
    }

    @Synchronized
    fun getAll(): List<DataPacketItem> {
        return buffer.toList()
    }

    /**
     * Pops until [sequence] (inclusive).
     */
    @Synchronized
    fun popToSequence(sequence: Int): List<DataPacketItem> {
        val retList = mutableListOf<DataPacketItem>()
        while (buffer.isNotEmpty()) {
            val first = buffer.first
            if (first.sequence <= sequence) {
                val item = dequeue()
                retList.add(item!!)
            } else {
                break
            }
        }

        return retList
    }

    @Synchronized
    fun trim(size: Long) {
        while (buffer.isNotEmpty() && totalSize > size + extraCapacity) {
            dequeue()
        }
    }

    /**
     * Returns the total byte size of the items in the buffer.
     */
    @Synchronized
    fun byteSize() = totalSize

    /**
     * Returns the number of items in the buffer
     */
    @Synchronized
    fun size() = buffer.size
}
