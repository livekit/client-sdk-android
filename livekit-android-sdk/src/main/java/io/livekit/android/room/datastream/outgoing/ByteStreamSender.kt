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

import androidx.annotation.CheckResult
import io.livekit.android.room.datastream.ByteStreamInfo
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.Source
import okio.source
import java.io.InputStream
import java.util.Arrays
import kotlin.math.min

class ByteStreamSender(
    val info: ByteStreamInfo,
    destination: StreamDestination<ByteArray>,
) : BaseStreamSender<ByteArray>(destination = destination) {

    override suspend fun writeImpl(data: ByteArray): Result<Unit> {
        return destination.write(data, byteDataChunker)
    }
}

private val byteDataChunker: DataChunker<ByteArray> = { data: ByteArray, chunkSize: Int ->
    (data.indices step chunkSize)
        .map { index ->
            Arrays.copyOfRange(
                /* original = */
                data,
                /* from = */
                index,
                /* to = */
                min(index + chunkSize, data.size),
            )
        }
}

/**
 * Reads the file from [filePath] and writes it to the data stream.
 */
@CheckResult
suspend fun ByteStreamSender.writeFile(filePath: String): Result<Unit> {
    return write(FileSystem.SYSTEM.source(filePath.toPath()))
}

/**
 * Reads the input stream and sends it to the data stream.
 */
@CheckResult
suspend fun ByteStreamSender.write(input: InputStream): Result<Unit> {
    return write(input.source())
}

/**
 * Reads the source and sends it to the data stream.
 */
@CheckResult
suspend fun ByteStreamSender.write(source: Source): Result<Unit> {
    val buffer = Buffer()
    while (true) {
        try {
            val readLen = source.read(buffer, 4096)
            if (readLen == -1L) {
                break
            }

            val result = write(buffer.readByteArray())
            if (result.isFailure) {
                return result
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    return Result.success(Unit)
}
