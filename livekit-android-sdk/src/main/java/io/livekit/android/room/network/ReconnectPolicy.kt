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

/**
 * Policy for reconnections that determines the delay between retries.
 */
interface ReconnectPolicy {
    /**
     * Called after a disconnect is detected, and between each reconnect attempt.
     *
     * Note: To prevent infinitely retrying, there is a hard cap of 30 retries, regardless of policy.
     *
     * @return The [Duration] to delay before the next reconnect attempt, or null to cancel reconnections.
     *
     */
    fun getNextRetryDelay(context: ReconnectContext): Duration?
}

data class ReconnectContext(
    /**
     * The number of failed reconnect attempts. 0 means this is the first reconnect attempt.
     */
    val retryCount: Int,

    /**
     * Elapsed amount of time in milliseconds since the disconnect.
     */
    val elapsedTime: Duration,
)
