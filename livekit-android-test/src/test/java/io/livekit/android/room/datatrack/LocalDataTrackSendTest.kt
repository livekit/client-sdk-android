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

package io.livekit.android.room.datatrack

import io.livekit.android.test.BaseTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sink that rejects on demand, so the queue-full policy can be observed without saturating a
 * live pipeline (which would make the outcome depend on how fast the SFU drains).
 */
private class RecordingSink(
    published: Boolean = true,
    private val unpublishAfter: Int? = null,
    private val reject: (DataTrackFrame) -> Boolean = { false },
) : DataTrackFrameSink {
    val offered = mutableListOf<DataTrackFrame>()
    val accepted = mutableListOf<DataTrackFrame>()

    override var isPublished: Boolean = published
        private set

    override fun tryPush(frame: DataTrackFrame): Result<Unit> {
        offered.add(frame)
        if (reject(frame)) {
            return Result.failure(
                DataTrackPushFrameException.QueueFull("The send queue is full", frame),
            )
        }
        accepted.add(frame)
        if (unpublishAfter != null && accepted.size >= unpublishAfter) {
            isPublished = false
        }
        return Result.success(Unit)
    }
}

class LocalDataTrackSendTest : BaseTest() {

    @Test
    fun dropSkipsRejectedFrames() = runTest {
        val sink = RecordingSink { it.payload.contentEquals(byteArrayOf(2)) }

        val result = sink.sendFrames(frames(5), LocalDataTrack.FrameDropPolicy.DROP)

        assertTrue(result.isSuccess)
        assertEquals(5, sink.offered.size)
        assertPayloads(listOf(0, 1, 3, 4), sink.accepted)
    }

    @Test
    fun failStopsAtRejectedFrame() = runTest {
        val sink = RecordingSink { it.payload.contentEquals(byteArrayOf(2)) }

        val result = sink.sendFrames(frames(5), LocalDataTrack.FrameDropPolicy.FAIL)

        val error = result.exceptionOrNull() as DataTrackPushFrameException.QueueFull
        assertArrayEquals(byteArrayOf(2), error.frame.payload)
        assertPayloads(listOf(0, 1), sink.accepted)
    }

    @Test
    fun unpublishingEndsSendQuietlyWhenDropping() = runTest {
        unpublishingEndsSendQuietly(LocalDataTrack.FrameDropPolicy.DROP)
    }

    @Test
    fun unpublishingEndsSendQuietlyWhenFailing() = runTest {
        unpublishingEndsSendQuietly(LocalDataTrack.FrameDropPolicy.FAIL)
    }

    @Test
    fun unpublishedTrackSendsNothing() = runTest {
        val sink = RecordingSink(published = false)

        val result = sink.sendFrames(frames(3), LocalDataTrack.FrameDropPolicy.FAIL)

        assertTrue(result.isSuccess)
        assertTrue(sink.offered.isEmpty())
    }

    private suspend fun unpublishingEndsSendQuietly(policy: LocalDataTrack.FrameDropPolicy) {
        val sink = RecordingSink(unpublishAfter = 1)

        val result = sink.sendFrames(frames(5), policy)

        assertTrue(result.isSuccess)
        assertEquals(1, sink.accepted.size)
    }

    companion object {
        private fun frames(count: Int): Flow<DataTrackFrame> = flow {
            for (index in 0 until count) {
                emit(DataTrackFrame(byteArrayOf(index.toByte())))
            }
        }

        private fun assertPayloads(expected: List<Int>, frames: List<DataTrackFrame>) {
            assertEquals(expected.size, frames.size)
            expected.zip(frames).forEach { (value, frame) ->
                assertArrayEquals(byteArrayOf(value.toByte()), frame.payload)
            }
        }
    }
}
