package io.livekit.android.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect

class BroadcastEventBus<T> : EventListenable<T> {
    private val mutableEvents = MutableSharedFlow<T>()
    override val events = mutableEvents.asSharedFlow()

    suspend fun postEvent(event: T) {
        mutableEvents.emit(event)
    }

    suspend fun postEvents(eventsToPost: Collection<T>) {
        eventsToPost.forEach { event ->
            mutableEvents.emit(event)
        }
    }

    fun readOnly(): EventListenable<T> = this
}