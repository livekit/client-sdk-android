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

package io.livekit.android.room.datastream.outgoing

import androidx.annotation.CheckResult
import io.livekit.android.room.datastream.ByteStreamInfo
import io.livekit.android.room.datastream.DataStreams
import io.livekit.android.room.datastream.StreamBytesOptions
import io.livekit.android.room.datastream.StreamException
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.datastream.TextStreamInfo
import io.livekit.android.util.rethrowIfCancellationSignal
import java.io.File
import java.io.InputStream
import javax.inject.Inject

interface OutgoingDataStreamManager {
    /**
     * Start sending a stream of text. Call [TextStreamSender.close] when finished sending.
     *
     * @see [TextStreamSender.write]
     * @throws StreamException if the stream failed to open.
     */
    suspend fun streamText(options: StreamTextOptions = StreamTextOptions()): TextStreamSender

    /**
     * Start sending a stream of bytes. Call [ByteStreamSender.close] when finished sending.
     *
     * Extension functions are available for sending bytes from sources such as [InputStream] or [File].
     *
     * @see [ByteStreamSender.write]
     * @see [ByteStreamSender.writeFile]
     * @throws StreamException if the stream failed to open.
     */
    suspend fun streamBytes(options: StreamBytesOptions): ByteStreamSender

    /**
     * Send text through a data stream.
     */
    @CheckResult
    suspend fun sendText(text: String, options: StreamTextOptions = StreamTextOptions()): Result<TextStreamInfo> {
        return useStreamSender(streamText(options)) {
            val result = write(text)
            if (result.isFailure) {
                throw (result.exceptionOrNull() ?: Exception("Unknown error."))
            }
            close()
            return@useStreamSender info
        }
    }

    /**
     * Send a byte payload through a data stream.
     *
     * Prefer this over opening a stream with [streamBytes] when the whole payload is already in
     * memory: because the size is known up front, it can be sent as a single packet and compressed,
     * where recipients support it.
     */
    @CheckResult
    suspend fun sendBytes(data: ByteArray, options: StreamBytesOptions = StreamBytesOptions()): Result<ByteStreamInfo> {
        return useStreamSender(streamBytes(options)) {
            val result = write(data)
            if (result.isFailure) {
                throw (result.exceptionOrNull() ?: Exception("Unknown error."))
            }
            close()
            return@useStreamSender info
        }
    }

    /**
     * Send a file through a data stream.
     */
    @CheckResult
    suspend fun sendFile(file: File, options: StreamBytesOptions = StreamBytesOptions()): Result<ByteStreamInfo> {
        return useStreamSender(streamBytes(options)) {
            val result = writeFile(file)
            if (result.isFailure) {
                throw (result.exceptionOrNull() ?: Exception("Unknown error."))
            }
            close()
            return@useStreamSender info
        }
    }
}

/**
 * Adapts [OutgoingDataStreamManager] onto [DataStreams], which owns the actual implementation.
 *
 * The whole-payload sends are overridden rather than left to the interface's default
 * open-write-close implementations, so that they reach the core's one-shot paths and can be sent as
 * a single packet and/or compressed.
 *
 * @suppress
 */
class OutgoingDataStreamManagerImpl @Inject constructor(
    private val dataStreams: DataStreams,
) : OutgoingDataStreamManager {

    override suspend fun streamText(options: StreamTextOptions): TextStreamSender {
        return dataStreams.streamText(options)
    }

    override suspend fun streamBytes(options: StreamBytesOptions): ByteStreamSender {
        return dataStreams.streamBytes(options)
    }

    override suspend fun sendText(text: String, options: StreamTextOptions): Result<TextStreamInfo> {
        return runCatchingStream { dataStreams.sendText(text, options) }
    }

    override suspend fun sendBytes(data: ByteArray, options: StreamBytesOptions): Result<ByteStreamInfo> {
        return runCatchingStream { dataStreams.sendBytes(data, options) }
    }

    override suspend fun sendFile(file: File, options: StreamBytesOptions): Result<ByteStreamInfo> {
        // Options are passed through untouched: the previous implementation did not infer a name,
        // MIME type or size from the file either, and doing so now would change the bytes on the
        // wire for existing callers.
        return runCatchingStream { dataStreams.sendFile(file.absolutePath, options) }
    }

    private inline fun <T> runCatchingStream(body: () -> T): Result<T> {
        return try {
            Result.success(body())
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            Result.failure(e)
        }
    }
}
