/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room.datatrack

import io.livekit.android.util.CloseableCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import io.livekit.uniffi.DataTrackStreamInterface as FfiDataTrackStream

/**
 * A stream of frames received from a subscribed [RemoteDataTrack].
 *
 * Collect [flow] or call [next] repeatedly. The stream ends when the track is unpublished or the
 * subscription is cancelled.
 *
 * Close the stream once you are done with it: the subscription lasts as long as the stream does,
 * so an unclosed stream leaves the SFU forwarding frames for it.
 *
 * ```
 * remoteTrack.subscribe().onSuccess { stream ->
 *     stream.use { it.flow.collect { frame -> process(frame.payload) } }
 * }
 * ```
 */
class DataTrackStream internal constructor(
    private val impl: FfiDataTrackStream,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {

    private val coroutineScope = CloseableCoroutineScope(dispatcher + SupervisorJob())

    /**
     * Set once no further frames will arrive, whether because the underlying stream was exhausted
     * or because [close] was called. Collectors watch this so they complete instead of waiting
     * for a frame that will never come, including those that arrive afterwards.
     */
    private val ended = MutableStateFlow(false)

    /**
     * Returns the next frame, or `null` once the stream ends (the track is unpublished or the
     * subscription is cancelled).
     */
    suspend fun next(): DataTrackFrame? {
        return impl.next()?.let { DataTrackFrame(it) }
    }

    /**
     * Drains the underlying stream while anyone is collecting [flow], so every collector sees
     * every frame.
     *
     * Emission suspends until every collector has taken the frame, so a slow one holds up the
     * drain rather than being skipped. While it is held up, frames accumulate in the buffer the
     * subscription was created with, and once that fills the oldest are dropped for all
     * collectors at once — see [RemoteDataTrack.subscribe]'s `bufferSize`.
     */
    private val sharedFrames: SharedFlow<DataTrackFrame> = flow {
        while (true) {
            val frame = next() ?: break
            emit(frame)
        }
        ended.value = true
    }.shareIn(coroutineScope, SharingStarted.WhileSubscribed(), replay = 0)

    /**
     * A [Flow] of incoming frames. Completes normally when the stream ends.
     *
     * Concurrent collectors each receive every frame that arrives while they are collecting;
     * frames are not replayed to a collector that starts late.
     */
    val flow: Flow<DataTrackFrame> = merge(
        sharedFrames,
        ended.filter { it }.map { null },
    ).takeWhile { it != null }.filterNotNull()

    /**
     * Ends this stream and releases it.
     *
     * The data track's subscription is dropped once every [DataTrackStream] subscribed
     * to it has been closed, so other subscribers are unaffected. Leaving a stream
     * unclosed keeps its subscription alive until the stream is garbage collected.
     *
     * Any in-progress collection of [flow] completes.
     */
    override fun close() {
        // Before cancelling the drain: cancelling it cannot complete the collectors, since it is
        // this flag rather than the drain finishing that ends them.
        ended.value = true
        coroutineScope.close()
        (impl as? AutoCloseable)?.close()
    }
}
