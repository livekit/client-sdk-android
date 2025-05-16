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

package io.livekit.android.room.datastream.outgoing

import io.livekit.android.room.datastream.StreamException

abstract class BaseStreamSender<T>(
    internal val destination: StreamDestination<T>,
) {

    suspend fun write(data: T) {
        if (!destination.isOpen) {
            throw StreamException.TerminatedException()
        }

        writeImpl(data)
    }

    internal abstract suspend fun writeImpl(data: T)
    suspend fun close(reason: String? = null) {
        destination.close(reason)
    }
}

/**
 * @suppress
 */
interface StreamDestination<T> {
    val isOpen: Boolean
    suspend fun write(data: T, chunker: DataChunker<T>)
    suspend fun close(reason: String?)
}

/**
 * @suppress
 */
typealias DataChunker<T> = (data: T, chunkSize: Int) -> List<ByteArray>
