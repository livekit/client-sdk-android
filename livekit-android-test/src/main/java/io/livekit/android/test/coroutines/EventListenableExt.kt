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

package io.livekit.android.test.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Collect all items until signal is given.
 */
suspend fun <T> Flow<T>.toListUntilSignal(signal: Flow<Unit?>): List<T> {
    return takeUntilSignal(signal)
        .fold(emptyList()) { list, event ->
            list.plus(event)
        }
}

fun <T> Flow<T>.takeUntilSignal(signal: Flow<Unit?>): Flow<T> = flow {
    try {
        coroutineScope {
            launch {
                signal.takeWhile { it == null }.collect()
                println("signalled")
                this@coroutineScope.cancel()
            }

            collect {
                emit(it)
            }
        }
    } catch (e: CancellationException) {
        // ignore
    }
}
