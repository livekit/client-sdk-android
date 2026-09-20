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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    /**
     * The end-of-stream signal must reach collectors behind the frames, never ahead of them.
     *
     * `shareIn` buffers frames (64 by default), so the drain finishes producing long before a
     * collector has taken delivery. An out-of-band terminator — setting a flag that [flow] merges
     * in — then races those buffered frames and usually wins, completing collection early. A
     * single-frame stream can deliver nothing at all.
     *
     * This runs on a real multi-threaded dispatcher and repeats: the other tests here use
     * [UnconfinedTestDispatcher], whose deterministic ordering hides the race entirely.
     */
    @Test
    fun everyFrameSurvivesTheEndOfStream() = runTest {
        for (frameCount in listOf(1, 2, 17)) {
            val incomplete = mutableListOf<List<Int>>()
            repeat(150) {
                val fake = FakeFfiStream()
                val stream = DataTrackStream(fake, Dispatchers.Default)
                repeat(frameCount) { i -> fake.offer(i + 1) }
                fake.end()

                val received = withContext(Dispatchers.Default) {
                    withTimeout(5_000) { stream.flow.toList() }
                }
                if (received.size != frameCount) {
                    incomplete.add(payloads(received))
                }
            }
            assertEquals(
                "collections that lost frames with frameCount=$frameCount: $incomplete",
                emptyList<List<Int>>(),
                incomplete,
            )
        }
    }

    /**
     * A collector that arrives after the drain has finished must complete, not wait forever.
     *
     * [collectingAfterTheStreamEndedCompletesImmediately] covers the easy case, where the earlier
     * collector has already finished: the subscriber count reaches zero, so `WhileSubscribed`
     * restarts the drain, which ends again immediately. This covers the case it misses — a slow
     * collector still holding the count above zero. No restart happens then, so the end-of-stream
     * signal has to be state the late collector can still observe rather than an event it missed.
     */
    @Test
    fun aLateCollectorOverlappingASlowOneStillCompletes() = runTest {
        withContext(Dispatchers.Default) {
            val fake = FakeFfiStream()
            val stream = DataTrackStream(fake, Dispatchers.Default)

            val slow = async(Dispatchers.Default) {
                val got = mutableListOf<DataTrackFrame>()
                stream.flow.collect { got.add(it); delay(60) }
                got
            }
            delay(50)
            repeat(10) { fake.offer(it + 1) }
            fake.end()
            // The drain is done producing well before the slow collector has worked through it.
            delay(150)

            val late = async(Dispatchers.Default) { stream.flow.toList() }

            assertEquals(10, withTimeout(5_000) { slow.await() }.size)
            // Nothing is left to deliver, but it must still complete rather than hang.
            assertEquals(emptyList<Int>(), payloads(withTimeout(5_000) { late.await() }))
        }
    }

    /**
     * The counterpart to [everyFrameSurvivesTheEndOfStream]: [DataTrackStream.close] is the one
     * case where completing ahead of buffered frames is correct, since the caller asked to stop.
     * It must still end collection promptly rather than draining first.
     */
    @Test
    fun closeEndsCollectionPromptlyOnARealDispatcher() = runTest {
        withContext(Dispatchers.Default) {
            val fake = FakeFfiStream()
            val stream = DataTrackStream(fake, Dispatchers.Default)
            val collecting = async(Dispatchers.Default) { stream.flow.toList() }

            fake.offer(1)
            // Give the collector a moment to subscribe and take the frame before closing.
            delay(200)
            stream.close()

            withTimeout(5_000) { collecting.await() }
        }
    }
}
