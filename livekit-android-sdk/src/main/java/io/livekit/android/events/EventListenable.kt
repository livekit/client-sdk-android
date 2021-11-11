package io.livekit.android.events

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect

interface EventListenable<out T> {
    val events: SharedFlow<T>
}

suspend inline fun <T> EventListenable<T>.collect(crossinline action: suspend (value: T) -> Unit) {
    return events.collect(action)
}