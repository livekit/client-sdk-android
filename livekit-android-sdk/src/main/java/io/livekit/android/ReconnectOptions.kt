package io.livekit.android

import io.livekit.android.room.Room

/**
 * Reconnect options for using with [Room.connect].
 */

private const val MAX_RECONNECT_RETRIES = 10
private const val MAX_RECONNECT_TIMEOUT = 60 * 1000L

data class ReconnectOptions(
    /** Max reconnect retries, defaults to 10 */
    val maxRetries: Int = MAX_RECONNECT_RETRIES,
    /** Max reconnect timeout in milliseconds, defaults to 60 seconds */
    val reconnectTimeout: Long = MAX_RECONNECT_TIMEOUT,
    /** Allow full reconnect, defaults to false */
    val allowFullReconnect : Boolean = false,
)
