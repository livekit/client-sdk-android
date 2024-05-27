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

package io.livekit.android.test.events

import io.livekit.android.test.coroutines.toListUntilSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

open class FlowCollector<T>(
    private val flow: Flow<T>,
    coroutineScope: CoroutineScope,
) {
    private val signal = MutableStateFlow<Unit?>(null)
    private val collectEventsDeferred = coroutineScope.async {
        flow.toListUntilSignal(signal)
    }

    /**
     * Stop collecting events. returns the events collected.
     */
    suspend fun stopCollecting(): List<T> {
        signal.compareAndSet(null, Unit)
        return collectEventsDeferred.await()
    }
}
