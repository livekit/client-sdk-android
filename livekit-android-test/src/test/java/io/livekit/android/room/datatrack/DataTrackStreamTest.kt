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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.livekit.uniffi.DataTrackFrame as FfiDataTrackFrame
import io.livekit.uniffi.DataTrackStreamInterface as FfiDataTrackStream

/**
 * A stand-in for the UniFFI stream, so frame delivery can be driven from the test rather than
 * from a live subscription.
 */
private class FakeFfiStream : FfiDataTrackStream {
    private val frames = Channel<FfiDataTrackFrame>(Channel.UNLIMITED)

    override suspend fun next(): FfiDataTrackFrame? = frames.receiveCatching().getOrNull()

    fun offer(payload: Int) {
        frames.trySend(FfiDataTrackFrame(payload = byteArrayOf(payload.toByte()), userTimestamp = null))
    }

    /** Ends the stream, the way an unpublish or a cancelled subscription would. */
    fun end() {
        frames.close()
    }
}

class DataTrackStreamTest : BaseTest() {

    private fun payloads(frames: List<DataTrackFrame>) = frames.map { it.payload.single().toInt() }

    @Test
    fun concurrentCollectorsEachReceiveEveryFrame() = runTest {
        val fake = FakeFfiStream()
        val stream = DataTrackStream(fake, UnconfinedTestDispatcher(testScheduler))
        val first = mutableListOf<DataTrackFrame>()
        val second = mutableListOf<DataTrackFrame>()

        // Unconfined so both collectors are subscribed before any frame is offered.
        val firstJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.flow.collect { first.add(it) }
        }
        val secondJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.flow.collect { second.add(it) }
        }

        fake.offer(1)
        fake.offer(2)
        fake.offer(3)
        fake.end()
        advanceUntilIdle()
        firstJob.join()
        secondJob.join()

        assertEquals(listOf(1, 2, 3), payloads(first))
        assertEquals(listOf(1, 2, 3), payloads(second))
    }

    @Test
    fun collectorCompletesWhenStreamEnds() = runTest {
        val fake = FakeFfiStream()
        val stream = DataTrackStream(fake, UnconfinedTestDispatcher(testScheduler))
        val received = mutableListOf<DataTrackFrame>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.flow.collect { received.add(it) }
        }
        fake.offer(1)
        fake.end()
        advanceUntilIdle()
        job.join()

        assertEquals(listOf(1), payloads(received))
    }

    @Test
    fun aLaterCollectorResumesTheDrainAfterAnEarlierOneStopped() = runTest {
        val fake = FakeFfiStream()
        val stream = DataTrackStream(fake, UnconfinedTestDispatcher(testScheduler))
        val first = mutableListOf<DataTrackFrame>()
        val second = mutableListOf<DataTrackFrame>()

        val firstJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.flow.collect { first.add(it) }
        }
        fake.offer(1)
        advanceUntilIdle()
        // Drops the subscriber count to zero, which cancels the drain mid-`next()`.
        firstJob.cancelAndJoin()

        val secondJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.flow.collect { second.add(it) }
        }
        fake.offer(2)
        fake.end()
        advanceUntilIdle()
        secondJob.join()

        assertEquals(listOf(1), payloads(first))
        // Empty here would mean the cancelled drain had wrongly marked the stream ended.
        assertEquals(listOf(2), payloads(second))
    }

    @Test
    fun closingWhileCollectingCompletesTheCollector() = runTest {
        val fake = FakeFfiStream()
        val stream = DataTrackStream(fake, UnconfinedTestDispatcher(testScheduler))
        val received = mutableListOf<DataTrackFrame>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.flow.collect { received.add(it) }
        }
        fake.offer(1)
        advanceUntilIdle()
        stream.close()
        advanceUntilIdle()

        assertEquals(listOf(1), payloads(received))
        assertTrue("collector should have completed after close()", job.isCompleted)
    }

    @Test
    fun collectingAfterTheStreamEndedCompletesImmediately() = runTest {
        val fake = FakeFfiStream()
        val stream = DataTrackStream(fake, UnconfinedTestDispatcher(testScheduler))

        val job = launch(UnconfinedTestDispatcher(testScheduler)) { stream.flow.collect { } }
        fake.end()
        advanceUntilIdle()
        job.join()

        // Would hang if termination depended on a signal the late collector had already missed.
        val late = mutableListOf<DataTrackFrame>()
        stream.flow.collect { late.add(it) }

        assertEquals(emptyList<Int>(), payloads(late))
    }
}
