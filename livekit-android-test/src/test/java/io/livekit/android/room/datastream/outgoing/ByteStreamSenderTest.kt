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

package io.livekit.android.room.datastream.outgoing

import io.livekit.android.room.datastream.ByteStreamInfo
import io.livekit.android.test.BaseTest
import io.livekit.android.test.mock.room.datastream.outgoing.MockStreamDestination
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.roundToInt

class ByteStreamSenderTest : BaseTest() {

    companion object {
        val CHUNK_SIZE = 2048
    }

    @Test
    fun sendsSmallBytes() = runTest {
        val destination = MockStreamDestination<ByteArray>(CHUNK_SIZE)
        val sender = ByteStreamSender(
            info = createInfo(),
            destination = destination,
        )

        val job = launch {
            sender.write(ByteArray(100))
            sender.close()
        }

        job.join()

        assertFalse(destination.isOpen)
        assertEquals(1, destination.writtenChunks.size)
        assertEquals(100, destination.writtenChunks[0].size)
    }

    @Test
    fun sendsLargeBytes() = runTest {
        val destination = MockStreamDestination<ByteArray>(CHUNK_SIZE)
        val sender = ByteStreamSender(
            info = createInfo(),
            destination = destination,
        )

        val bytes = ByteArray((CHUNK_SIZE * 1.5).roundToInt())

        val job = launch {
            sender.write(bytes)
            sender.close()
        }

        job.join()

        assertFalse(destination.isOpen)
        assertEquals(2, destination.writtenChunks.size)
        assertEquals(CHUNK_SIZE, destination.writtenChunks[0].size)
        assertEquals(bytes.size - CHUNK_SIZE, destination.writtenChunks[1].size)
    }

    fun createInfo(): ByteStreamInfo = ByteStreamInfo(id = "stream_id", topic = "topic", timestampMs = 0, totalSize = null, attributes = mapOf(), mimeType = "", name = null)
}
