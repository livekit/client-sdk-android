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

import io.livekit.android.room.datastream.TextStreamInfo
import io.livekit.android.test.BaseTest
import io.livekit.android.test.mock.room.datastream.outgoing.MockStreamDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextStreamSenderTest : BaseTest() {

    companion object {
        val CHUNK_SIZE = 20
    }

    @Test
    fun sendsSingle() = runTest {
        val destination = MockStreamDestination<String>(CHUNK_SIZE)
        val sender = TextStreamSender(
            info = createInfo(),
            destination = destination,
        )

        val text = "abcdefghi"
        val result = sender.write(text)
        assertTrue(result.isSuccess)
        sender.close()

        assertFalse(destination.isOpen)
        assertEquals(1, destination.writtenChunks.size)
        assertEquals(text, destination.writtenChunks[0].decodeToString())
    }

    @Test
    fun sendsChunks() = runTest {
        val destination = MockStreamDestination<String>(CHUNK_SIZE)
        val sender = TextStreamSender(
            info = createInfo(),
            destination = destination,
        )

        val text = with(StringBuilder()) {
            for (i in 1..CHUNK_SIZE) {
                append("abcdefghi")
            }
            toString()
        }

        val result = sender.write(text)
        assertTrue(result.isSuccess)
        sender.close()

        assertFalse(destination.isOpen)
        assertNotEquals(1, destination.writtenChunks.size)

        val writtenString = with(StringBuilder()) {
            for (chunk in destination.writtenChunks) {
                append(chunk.decodeToString())
            }
            toString()
        }

        assertEquals(text, writtenString)
    }

    @Test
    fun writeFailsAfterClose() = runTest {
        val destination = MockStreamDestination<String>(CHUNK_SIZE)
        val sender = TextStreamSender(
            info = createInfo(),
            destination = destination,
        )

        val text = "abcdefghi"
        assertTrue(sender.write(text).isSuccess)
        sender.close()

        assertTrue(sender.write(text).isFailure)

        assertFalse(destination.isOpen)
        assertEquals(1, destination.writtenChunks.size)
        assertEquals(text, destination.writtenChunks[0].decodeToString())
    }

    fun createInfo(): TextStreamInfo = TextStreamInfo(
        id = "stream_id",
        topic = "topic",
        timestampMs = 0,
        totalSize = null,
        attributes = mapOf(),
        operationType = TextStreamInfo.OperationType.CREATE,
        version = 0,
        replyToStreamId = null,
        attachedStreamIds = listOf(),
        generated = false,
    )
}
