package io.livekit.android.util

import kotlinx.coroutines.delay

suspend fun <T>executeSuspendWithRetry(maxRetries: Int, suspendFunction: suspend () -> T): T {
    var currentAttempt = 0
    var lastException: Exception? = null

    while (currentAttempt <= maxRetries) {
        try {
            return suspendFunction()
        } catch (e: Exception) {
            LKLog.i {"connection number $currentAttempt failed with $e"}
            // Store the last exception for error logging
            lastException = e
            currentAttempt++

            // Delay before retrying
            delay(1_000L * currentAttempt)
        }
    }

    throw lastException ?: RuntimeException("Max retries exceeded")
}
