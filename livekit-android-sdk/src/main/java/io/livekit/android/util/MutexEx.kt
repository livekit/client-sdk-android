package io.livekit.android.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies a double-checked lock before running [action].
 */
suspend inline fun <T> Mutex.withCheckLock(check: () -> Unit, action: () -> T): T {
    check()
    return withLock {
        check()
        action()
    }
}
