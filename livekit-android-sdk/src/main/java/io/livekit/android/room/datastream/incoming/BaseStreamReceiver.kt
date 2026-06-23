/*
 * Copyright 2025-2026 LiveKit, Inc.
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

import io.livekit.android.room.datastream.StreamException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold

/**
 * Base class for reading incoming data streams.
 *
 * @see [flow]
 * @see [readNext]
 * @see [readAll]
 */
abstract class BaseStreamReceiver<T>(private val source: Channel<ByteArray>) {

    /**
     * A [Flow] of stream data as it arrives.
     *
     * Collect this flow to process incoming data incrementally. The flow completes normally when
     * the stream receives all the data without error. If the stream ends abnormally, the
     * flow fails with a [StreamException] after all buffered chunks have been emitted.
     *
     * Example:
     * ```
     * reader.flow
     *     .catch { e ->
     *         if (e is StreamException) {
     *             handleStreamError(e)
     *         } else {
     *             throw e
     *         }
     *     }
     *     .collect { chunk -> process(chunk) }
     * ```
     */
    abstract val flow: Flow<T>

    internal fun close(error: Exception?) {
        source.close(cause = error)
    }

    /**
     * Suspends and waits for the next piece of data.
     *
     * @return the next available piece of data.
     * @throws NoSuchElementException when the stream is closed normally and no more data is available.
     * @throws StreamException when the stream is closed abnormally.
     */
    suspend fun readNext(): T {
        return flow.first()
    }

    /**
     * Suspends and waits for all available data until the stream is closed.
     *
     * @return A list of all data received if the stream is closed normally.
     * @throws StreamException when the stream is closed abnormally.
     */
    suspend fun readAll(): List<T> {
        return flow.fold(mutableListOf()) { acc, value ->
            acc.add(value)
            return@fold acc
        }
    }
}
