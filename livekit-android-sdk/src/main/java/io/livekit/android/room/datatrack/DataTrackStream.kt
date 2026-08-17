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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import io.livekit.uniffi.DataTrackStream as FfiDataTrackStream

/**
 * A stream of frames received from a subscribed [RemoteDataTrack].
 *
 * Collect [flow] or call [next] repeatedly. The stream ends when the track is unpublished or the
 * subscription is cancelled. [flow] is a single consumer: frames are not replayed, and a second
 * collector only sees frames that arrive after it starts.
 *
 * ```
 * val stream = remoteTrack.subscribe()
 * stream.flow.collect { frame -> process(frame.payload) }
 * ```
 */
class DataTrackStream internal constructor(
    private val impl: FfiDataTrackStream,
) {
    /**
     * Returns the next frame, or `null` once the stream ends (the track is unpublished or the
     * subscription is cancelled).
     */
    suspend fun next(): DataTrackFrame? {
        return impl.next()?.let { DataTrackFrame(it) }
    }

    /**
     * A [Flow] of incoming frames. Completes normally when the stream ends.
     */
    val flow: Flow<DataTrackFrame> = flow {
        while (true) {
            val frame = next() ?: break
            emit(frame)
        }
    }
}
