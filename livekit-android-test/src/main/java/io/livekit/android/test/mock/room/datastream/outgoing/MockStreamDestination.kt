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

package io.livekit.android.test.mock.room.datastream.outgoing

import io.livekit.android.room.datastream.StreamException
import io.livekit.android.room.datastream.outgoing.DataChunker
import io.livekit.android.room.datastream.outgoing.StreamDestination

class MockStreamDestination<T>(val chunkSize: Int) : StreamDestination<T> {
    override var isOpen: Boolean = true

    val writtenChunks = mutableListOf<ByteArray>()
    override suspend fun write(data: T, chunker: DataChunker<T>): Result<Unit> {
        if (!isOpen) {
            return Result.failure(StreamException.TerminatedException())
        }

        val chunks = chunker.invoke(data, chunkSize)

        for (chunk in chunks) {
            writtenChunks.add(chunk)
        }

        return Result.success(Unit)
    }

    override suspend fun close(reason: String?) {
        isOpen = false
    }
}
