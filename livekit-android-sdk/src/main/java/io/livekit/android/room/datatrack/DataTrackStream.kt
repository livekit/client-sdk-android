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
import kotlinx.coroutines.flow.onSubscription
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
     * Set once the drain has run the underlying stream to exhaustion.
     *
     * This exists only for collectors that arrive too late to see the terminator [sharedFrames]
     * emits.
     */
    private val drained = MutableStateFlow(false)

    /**
     * Set by [close]. Unlike [drained] this *is* merged into [flow], because closing cancels the
     * drain mid-[next], so no terminator is ever emitted and nothing in the frame stream would
     * complete collectors.
     *
     * Racing undelivered frames is correct here, and is the documented difference between the two
     * paths: the caller asked to stop, so collection ends promptly rather than draining first.
     */
    private val closed = MutableStateFlow(false)

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
     */
    private val sharedFrames: SharedFlow<DataTrackFrame?> = flow {
        while (true) {
            val frame = next() ?: break
            emit(frame)
        }
        // Before the terminator, so a collector that misses the terminator sees this instead.
        drained.value = true
        emit(null)
    }.shareIn(coroutineScope, SharingStarted.WhileSubscribed(), replay = 0)

    /**
     * A [Flow] of incoming frames. Completes normally when the stream ends.
     *
     * Concurrent collectors each receive every frame that arrives while they are collecting;
     * frames are not replayed to a collector that starts late.
     *
     * Completes when the stream is unpublished, or when [close] is called.
     */
    val flow: Flow<DataTrackFrame> = merge(
        // onSubscription runs after this collector holds a subscription but before it takes any
        // value, so a `false` reading here means the terminator is still coming to it.
        sharedFrames.onSubscription { if (drained.value) emit(null) },
        closed.filter { it }.map { null },
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
        // Before cancelling the drain: cancelling strands it inside next(), so it never emits a
        // terminator. On this path this is the only thing that can complete collectors.
        closed.value = true
        coroutineScope.close()
        (impl as? AutoCloseable)?.close()
    }
}
