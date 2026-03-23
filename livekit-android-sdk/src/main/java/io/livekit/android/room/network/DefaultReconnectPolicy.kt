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
            0.milliseconds,
            300.milliseconds,
            300.milliseconds,
            300.milliseconds, // Aggressively try to reconnect a couple of times. Wifi -> LTE handoff can randomly take a while.
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
