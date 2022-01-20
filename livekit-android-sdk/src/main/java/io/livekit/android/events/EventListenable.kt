package io.livekit.android.events

import kotlinx.coroutines.flow.SharedFlow

interface EventListenable<out T> {
    val events: SharedFlow<T>
}

suspend inline fun <T> EventListenable<T>.collect(crossinline action: suspend (value: T) -> Unit) {
    events.collect { value -> action(value) }
}