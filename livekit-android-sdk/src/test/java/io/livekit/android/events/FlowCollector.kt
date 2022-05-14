package io.livekit.android.events

import io.livekit.android.coroutines.toListUntilSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

open class FlowCollector<T>(
    private val flow: Flow<T>,
    coroutineScope: CoroutineScope
) {
    private val signal = MutableStateFlow<Unit?>(null)
    private val collectEventsDeferred = coroutineScope.async {
        flow.toListUntilSignal(signal)
    }

    /**
     * Stop collecting events. returns the events collected.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun stopCollecting(): List<T> {
        signal.compareAndSet(null, Unit)
        return collectEventsDeferred.await()
    }

}