package io.livekit.android.room.network

import kotlin.time.Duration

/**
 * Policy for reconnections that determines the delay between retries.
 */
interface ReconnectPolicy {
    /**
     * Called after a disconnect is detected, and between each reconnect attempt.
     *
     * Note: To prevent infinitely retrying, there is a hard cap of 100 retries, regardless of policy.
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
