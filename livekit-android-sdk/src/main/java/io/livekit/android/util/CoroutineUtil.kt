package io.livekit.android.util

import kotlinx.coroutines.*

fun <T, R> debounce(
    waitMs: Long = 300L,
    coroutineScope: CoroutineScope,
    destinationFunction: suspend (T) -> R
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

fun <R> ((Unit) -> R).invoke() {
    this.invoke(Unit)
}