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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A reconnect policy that takes in a list of delays to iterate through.
 */
class DefaultReconnectPolicy(
    /**
     * The list of delays to use. If the number of retries exceeds the size of the list,
     * reconnection is cancelled.
     *
     * Defaults to aggressively retrying multiple times before exponentially backing off, up to 5 seconds.
     */
    val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
    /**
     * The max total time to try reconnecting. Defaults to 60 seconds.
     */
    val maxReconnectionTimeout: Duration = DEFAULT_MAX_RECONNECTION_TIMEOUT,
) : ReconnectPolicy {
    override fun getNextRetryDelay(context: ReconnectContext): Duration? {
        if (context.retryCount >= retryDelays.size) {
            return null
        }

        if (context.elapsedTime > maxReconnectionTimeout) {
            return null
        }

        return retryDelays[context.retryCount]
    }

    companion object {

        val DEFAULT_MAX_RECONNECTION_TIMEOUT = 60.seconds

        val DEFAULT_MAX_RETRY_DELAY = 5.seconds

        val DEFAULT_RETRY_DELAYS = listOf(
            100.milliseconds,
            300.milliseconds, // Aggressively try to reconnect a couple of times. Wifi -> LTE handoff can randomly take a while.
            300.milliseconds,
            500.milliseconds,
            500.milliseconds,
            500.milliseconds,
            (2 * 2 * 300).milliseconds,
            (3 * 3 * 300).milliseconds,
            (4 * 4 * 300).milliseconds,
            DEFAULT_MAX_RETRY_DELAY,
            DEFAULT_MAX_RETRY_DELAY,
            DEFAULT_MAX_RETRY_DELAY,
            DEFAULT_MAX_RETRY_DELAY,
            DEFAULT_MAX_RETRY_DELAY,
            DEFAULT_MAX_RETRY_DELAY,
            DEFAULT_MAX_RETRY_DELAY,
            DEFAULT_MAX_RETRY_DELAY,
        )
    }
}
