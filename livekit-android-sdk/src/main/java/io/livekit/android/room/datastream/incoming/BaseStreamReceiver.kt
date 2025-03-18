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

package io.livekit.android.room.datastream.incoming

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold

abstract class BaseStreamReceiver<T>(private val source: Channel<ByteArray>) {

    abstract val flow: Flow<T>

    internal fun close(error: Exception?) {
        source.close(cause = error)
    }

    /**
     * Suspends and waits for the next piece of data.
     *
     * @return the next available piece of data.
     * @throws NoSuchElementException when the stream is closed and no more data is available.
     */
    suspend fun readNext(): T {
        return flow.first()
    }

    /**
     * Suspends and waits for all available data until the stream is closed.
     */
    suspend fun readAll(): List<T> {
        flow.catch { }
        return flow.fold(mutableListOf()) { acc, value ->
            acc.add(value)
            return@fold acc
        }
    }
}
