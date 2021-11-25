package io.livekit.android.events

import kotlinx.coroutines.CoroutineScope

class EventCollector<T : Event>(
    eventListenable: EventListenable<T>,
    coroutineScope: CoroutineScope
) : FlowCollector<T>(eventListenable.events, coroutineScope)