/*
 * Copyright 2023-2026 LiveKit, Inc.
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration

internal fun <T, R> debounce(
    waitMs: Long = 300L,
    coroutineScope: CoroutineScope,
    destinationFunction: suspend (T) -> R,
): (T) -> Unit {
    var debounceJob: Deferred<R>? = null
    return { param: T ->
        debounceJob?.cancel()
        debounceJob = coroutineScope.async {
            delay(waitMs)
            return@async destinationFunction(param)
        }
    }
}

internal fun <R> ((Unit) -> R).invoke() {
    this.invoke(Unit)
}
class TimeoutException(cause: Exception) : Exception(cause)

/**
 * A replacement for [withTimeout], as it throws a [TimeoutCancellationException], which is
 * a subclass of [CancellationException], and will cancel a coroutine entirely.
 *
 * This catches the [TimeoutCancellationException], and rethrows a [TimeoutException].
 *
 * See the following for context:
 * * [https://github.com/Kotlin/kotlinx.coroutines/issues/1374](https://github.com/Kotlin/kotlinx.coroutines/issues/1374)
 * * [https://github.com/Kotlin/kotlinx.coroutines/pull/4356](https://github.com/Kotlin/kotlinx.coroutines/pull/4356)
 * @throws TimeoutException if the [timeout] is exceeded.
 */

@Throws(TimeoutException::class)
@OptIn(ExperimentalContracts::class)
@Suppress("NoWithTimeout") // Only allowed call site: maps TimeoutCancellationException to TimeoutException (see KDoc).
internal suspend fun <T> withDeadline(timeout: Duration, block: suspend () -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    try {
        return withTimeout(timeout) { block() }
    } catch (e: TimeoutCancellationException) {
        throw TimeoutException(e)
    }
}

fun Throwable.rethrowIfCancellationSignal() {
    if (this is CancellationException) {
        throw this
    }
}
