package io.livekit.android.coroutines

import io.livekit.android.events.EventListenable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Collect events until signal is given.
 */
suspend fun <T> EventListenable<T>.collectEvents(signal: Flow<Unit?>): List<T> {
    return events.takeUntilSignal(signal)
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