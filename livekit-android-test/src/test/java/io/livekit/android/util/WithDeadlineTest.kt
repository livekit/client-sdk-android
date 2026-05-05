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

package io.livekit.android.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class WithDeadlineTest {

    @Test
    fun returnsWhenBlockFinishesBeforeDeadline() = runTest {
        val deferred = async {
            withDeadline(5000.milliseconds) {
                delay(100)
                42
            }
        }
        advanceUntilIdle()
        assertEquals(42, deferred.await())
    }

    @Test
    fun exceedsDeadlineThrowsTimeoutExceptionWithTimeoutCancellationCause() = runTest {
        val deferred = async {
            try {
                withDeadline(50.milliseconds) {
                    delay(1.hours)
                }
                null
            } catch (e: TimeoutException) {
                e
            }
        }
        advanceUntilIdle()
        val timeout = requireNotNull(deferred.await())
        assertTrue(timeout.cause is TimeoutCancellationException)
    }

    @Test
    fun externalCancellationPropagatesCancellationException() = runTest {
        val deferred = async {
            withDeadline(10.hours) {
                delay(Long.MAX_VALUE)
            }
        }
        advanceTimeBy(30.minutes.inWholeMilliseconds)
        deferred.cancel()
        advanceTimeBy(30.minutes.inWholeMilliseconds)
        try {
            deferred.await()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertTrue(e !is TimeoutCancellationException)
        }
    }
}
