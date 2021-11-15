package io.livekit.android.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class BroadcastEventBus<T> : EventListenable<T> {
    private val mutableEvents = MutableSharedFlow<T>()
    override val events = mutableEvents.asSharedFlow()

    suspend fun postEvent(event: T) {
        mutableEvents.emit(event)
    }

    fun postEvent(event: T, scope: CoroutineScope): Job {
        return scope.launch { postEvent(event) }
    }

    suspend fun postEvents(eventsToPost: Collection<T>) {
        eventsToPost.forEach { event ->
            mutableEvents.emit(event)
        }
    }

    fun postEvents(eventsToPost: Collection<T>, scope: CoroutineScope): Job {
        return scope.launch { postEvents(eventsToPost) }
    }

    fun readOnly(): EventListenable<T> = this
}