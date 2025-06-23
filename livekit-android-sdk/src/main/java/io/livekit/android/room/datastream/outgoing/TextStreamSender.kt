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

import io.livekit.android.room.datastream.TextStreamInfo
import java.util.Arrays

class TextStreamSender(
    val info: TextStreamInfo,
    destination: StreamDestination<String>,
) : BaseStreamSender<String>(destination) {
    override suspend fun writeImpl(data: String): Result<Unit> {
        return destination.write(data, stringChunker)
    }
}

private val stringChunker: DataChunker<String> = { text: String, chunkSize: Int ->
    val utf8Array = text.toByteArray(Charsets.UTF_8)

    val result = mutableListOf<ByteArray>()
    var startIndex = 0
    var endIndex = 0
    var i = 0
    while (i < utf8Array.size) {
        val nextHead = utf8Array[i].toInt()
        val nextCharPointSize = if ((nextHead and 0b1111_1000) == 0b1111_0000) {
            4
        } else if ((nextHead and 0b1111_0000) == 0b1110_0000) {
            3
        } else if ((nextHead and 0b1110_0000) == 0b1100_0000) {
            2
        } else {
            1
        }

        val curLength = endIndex - startIndex
        if (curLength + nextCharPointSize > chunkSize) {
            result.add(Arrays.copyOfRange(utf8Array, startIndex, endIndex))
            startIndex = endIndex
        }
        i += nextCharPointSize
        endIndex = i
    }

    // Last chunk done manually
    if (startIndex != endIndex) {
        result.add(Arrays.copyOfRange(utf8Array, startIndex, endIndex))
    }

    result
}
