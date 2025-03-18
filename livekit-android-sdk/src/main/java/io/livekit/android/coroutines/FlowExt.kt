/*
 * Copyright 2025 LiveKit, Inc.
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

package io.livekit.android.coroutines

import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

fun <T> Flow<T>.takeUntilSignal(signal: Flow<Unit?>): Flow<T> = flow {
    try {
        coroutineScope {
            launch {
                signal.takeWhile { it == null }.collect()
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

fun <T> Flow<T>.cancelOnSignal(signal: Flow<Unit?>): Flow<T> = flow {
    coroutineScope {
        launch {
            signal.takeWhile { it == null }.collect()
            currentCoroutineContext().cancel()
        }

        collect {
            emit(it)
        }
        currentCoroutineContext().cancel()
    }
}
