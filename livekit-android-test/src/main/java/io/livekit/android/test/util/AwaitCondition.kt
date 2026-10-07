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

package io.livekit.android.test.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Waiting primitives for work that spans both the test dispatcher and real threads.
 *
 * Data streams are handled by the Rust core, which resumes calls on threads of its own. That leaves
 * tests needing to make progress on two fronts at once, and neither of the usual tools does it:
 *
 *  - advancing the test scheduler ([TestCoroutineScheduler.advanceUntilIdle] and friends) does
 *    nothing for work sitting on a real thread, and
 *  - waiting in real time (`withContext(Dispatchers.Default) { delay(..) }`) parks the test body,
 *    and while it is parked nobody is running the test scheduler -- so a coroutine on the test
 *    dispatcher waiting to resume from the core never does, and the wait deadlocks.
 *
 * These helpers interleave the two: pump the scheduler, check, then hand real time to the other
 * threads with a blocking sleep on the test thread, and repeat. The sleep is deliberate; `delay`
 * here would consume virtual time and never yield the CPU.
 */

private const val POLL_MS = 2L

/**
 * Pumps [scheduler] and waits, in real time, for [condition] to hold.
 *
 * @throws AssertionError if [condition] has not become true within [timeoutMs].
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun awaitCondition(
    scheduler: TestCoroutineScheduler,
    timeoutMs: Long = 5_000L,
    message: String = "Condition was not met",
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        scheduler.runCurrent()
        if (condition()) {
            return
        }
        Thread.sleep(POLL_MS)
    }
    scheduler.runCurrent()
    if (!condition()) {
        throw AssertionError("$message (waited ${timeoutMs}ms)")
    }
}

/**
 * Pumps [scheduler] and waits until [snapshot] stops changing for [quietMs].
 *
 * For when a test needs everything in flight to finish but has no single condition to wait on -- in
 * particular before asserting something was *not* produced, where a fixed wait risks passing only
 * because the check ran too early.
 *
 * [minWaitMs] matters as much as the quiet period: before the work has started, [snapshot] is
 * trivially unchanging, and waiting for stability alone would return immediately having observed
 * nothing.
 *
 * @param snapshot typically a count of observed side effects, e.g. packets sent so far.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun awaitStable(
    scheduler: TestCoroutineScheduler,
    quietMs: Long = 100L,
    minWaitMs: Long = 200L,
    timeoutMs: Long = 5_000L,
    snapshot: () -> Any?,
) {
    val start = System.currentTimeMillis()
    val deadline = start + timeoutMs
    var last: Any? = Unit
    var stableSince = start
    while (System.currentTimeMillis() < deadline) {
        scheduler.runCurrent()
        val now = System.currentTimeMillis()
        val current = snapshot()
        if (current != last) {
            last = current
            stableSince = now
        }
        if (now - stableSince >= quietMs && now - start >= minWaitMs) {
            return
        }
        Thread.sleep(POLL_MS)
    }
    scheduler.runCurrent()
}
