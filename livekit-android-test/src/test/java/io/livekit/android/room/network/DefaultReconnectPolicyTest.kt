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

package io.livekit.android.room.network

import io.livekit.android.room.network.DefaultReconnectPolicy.Companion.DEFAULT_RETRY_DELAYS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DefaultReconnectPolicyTest {

    private fun delayAt(policy: DefaultReconnectPolicy, retryCount: Int): Duration? =
        policy.getNextRetryDelay(ReconnectContext(retryCount = retryCount, elapsedTime = Duration.ZERO))

    @Test
    fun `stops retrying once the delays are exhausted`() {
        val policy = DefaultReconnectPolicy()

        assertNull(delayAt(policy, DEFAULT_RETRY_DELAYS.size))
    }

    @Test
    fun `stops retrying once the reconnection timeout has elapsed`() {
        val policy = DefaultReconnectPolicy()

        assertNull(
            policy.getNextRetryDelay(
                ReconnectContext(retryCount = 0, elapsedTime = 61.seconds),
            ),
        )
    }

    @Test
    fun `spreads out clients that disconnect at the same moment`() {
        val policy = DefaultReconnectPolicy()

        // Every client retrying on the same schedule reconnects in lockstep, so the
        // delay must vary between callers rather than being a fixed constant.
        val observed = (1..100).map { delayAt(policy, 0) }.toSet()

        assertTrue(
            "every caller got the same first retry delay: $observed",
            observed.size > 1,
        )
    }

    @Test
    fun `keeps jitter proportional to the base delay`() {
        val policy = DefaultReconnectPolicy()

        for (retryCount in DEFAULT_RETRY_DELAYS.indices) {
            val base = DEFAULT_RETRY_DELAYS[retryCount]

            repeat(100) {
                val delay = delayAt(policy, retryCount)

                assertNotNull("no delay for retry $retryCount", delay)
                assertTrue(
                    "retry $retryCount delay $delay below half of $base",
                    delay!! >= base * 0.5,
                )
                assertTrue(
                    "retry $retryCount delay $delay above 1.5x of $base",
                    delay <= base * 1.5,
                )
            }
        }
    }

    @Test
    fun `gives a zero first delay a bounded random window`() {
        // A caller asking for an immediate first retry still needs the herd spread out,
        // but there is no base delay to scale, so a fixed window is used instead.
        val policy = DefaultReconnectPolicy(
            retryDelays = listOf(Duration.ZERO, 300.milliseconds),
        )

        val observed = (1..100).map {
            val delay = delayAt(policy, 0)
            assertNotNull(delay)
            assertTrue("zero-base delay $delay was negative", delay!! >= Duration.ZERO)
            assertTrue("zero-base delay $delay exceeded the window", delay < 500.milliseconds)
            delay
        }.toSet()

        assertTrue("zero-base first retry did not vary: $observed", observed.size > 1)
    }

    @Test
    fun `honours a custom delay list length`() {
        val policy = DefaultReconnectPolicy(retryDelays = listOf(100.milliseconds))

        assertNotNull(delayAt(policy, 0))
        assertNull(delayAt(policy, 1))
    }

    @Test
    fun `passes through an explicit zero at a later index`() {
        // Only the first retry gets the fixed window; a later zero is an explicit
        // request to retry immediately.
        val policy = DefaultReconnectPolicy(
            retryDelays = listOf(100.milliseconds, Duration.ZERO),
        )

        assertEquals(Duration.ZERO, delayAt(policy, 1))
    }
}
