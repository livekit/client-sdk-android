package io.livekit.android.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Collect all items until signal is given.
 */
suspend fun <T> Flow<T>.toListUntilSignal(signal: Flow<Unit?>): List<T> {
    return takeUntilSignal(signal)
        .fold(emptyList()) { list, event ->
            list.plus(event)
        }
}

fun <T> Flow<T>.takeUntilSignal(signal: Flow<Unit?>): Flow<T> = flow {
    try {
        coroutineScope {
            launch {
                signal.takeWhile { it == null }.collect()
                println("signalled")
                this@coroutineScope.cancel()
            }

            collect {
                emit(it)
            }
        }

    } catch (e: CancellationException) {
        //ignore
    }
}