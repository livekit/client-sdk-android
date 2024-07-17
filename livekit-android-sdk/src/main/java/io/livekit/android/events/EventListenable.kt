/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * An interface declaring that this object emits events that can be collected.
 * @see [EventListenable.collect]
 */
interface EventListenable<out T> {
    val events: SharedFlow<T>
}

/**
 * @see [Flow.collect]
 */
suspend inline fun <T> EventListenable<T>.collect(crossinline action: suspend (value: T) -> Unit): Nothing {
    events.collect { value -> action(value) }
}
