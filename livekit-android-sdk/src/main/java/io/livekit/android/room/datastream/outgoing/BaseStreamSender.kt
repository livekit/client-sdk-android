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
import io.livekit.android.room.datastream.StreamException
import io.livekit.android.util.LKLog
import io.livekit.android.util.rethrowIfCancellationSignal
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

abstract class BaseStreamSender<T>(
    internal val destination: StreamDestination<T>,
) {

    val isOpen: Boolean
        get() = destination.isOpen

    /**
     * Write to the stream.
     */
    @CheckResult
    suspend fun write(data: T): Result<Unit> {
        if (!destination.isOpen) {
            return Result.failure(StreamException.TerminatedException())
        }

        return writeImpl(data)
    }

    @CheckResult
    internal abstract suspend fun writeImpl(data: T): Result<Unit>

    suspend fun close(reason: String? = null) {
        destination.close(reason)
    }
}

/**
 * @suppress
 */
interface StreamDestination<T> {
    val isOpen: Boolean

    @CheckResult
    suspend fun write(data: T, chunker: DataChunker<T>): Result<Unit>
    suspend fun close(reason: String?)
}

/**
 * @suppress
 */
typealias DataChunker<T> = (data: T, chunkSize: Int) -> List<ByteArray>

/**
 * Runs [block] with [sender], then ensures [sender] is closed afterwards if it is still open.
 *
 * On success, [block] should still attempt to close [sender] when the stream is
 * finished normally. If it is left open, any exceptions thrown by [sender.close]
 * will be ignored.
 *
 * Any exceptions thrown within [block] will be caught and returned in the result.
 *
 * @return A successful [Result] object containing the return value of [block], or
 * a failure if any exceptions were thrown.
 */
@CheckResult
suspend inline fun <S : BaseStreamSender<*>, R> useStreamSender(
    sender: S,
    block: suspend S.() -> R,
): Result<R> {
    var abnormalCloseReason: String? = null
    try {
        return Result.success(sender.block())
    } catch (e: Exception) {
        abnormalCloseReason = e.localizedMessage
        e.rethrowIfCancellationSignal()
        return Result.failure(e)
    } finally {
        if (sender.isOpen) {
            try {
                withContext(NonCancellable) {
                    sender.close(abnormalCloseReason)
                }
            } catch (closeException: Exception) {
                // Best-effort cleanup; must not mask pending cancellation or errors.
                LKLog.w(closeException) { "Error when closing sender:" }
            }
        }
    }
}
