package io.livekit.android.events

import io.livekit.android.coroutines.toListUntilSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runBlockingTest

open class FlowCollector<T>(
    private val flow: Flow<T>,
    coroutineScope: CoroutineScope
) {
    val signal = MutableStateFlow<Unit?>(null)
    val collectEventsDeferred = coroutineScope.async {
        flow.toListUntilSignal(signal)
    }

    /**
     * Stop collecting events. returns the events collected.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopCollecting(): List<T> {
        signal.compareAndSet(null, Unit)
        var events: List<T> = emptyList()
        runBlockingTest {
            events = collectEventsDeferred.await()
        }
        return events
    }

}