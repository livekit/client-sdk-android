package io.livekit.android.events

import io.livekit.android.coroutines.collectEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runBlockingTest

class EventCollector<T : Event>(
    private val eventListenable: EventListenable<T>,
    coroutineScope: CoroutineScope
) {
    val signal = MutableStateFlow<Unit?>(null)
    val collectEventsDeferred = coroutineScope.async {
        eventListenable.collectEvents(signal)
    }

    /**
     * Stop collecting events. returns the events collected.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopCollectingEvents(): List<T> {
        signal.compareAndSet(null, Unit)
        var events: List<T> = emptyList()
        runBlockingTest {
            events = collectEventsDeferred.await()
        }
        return events
    }

}