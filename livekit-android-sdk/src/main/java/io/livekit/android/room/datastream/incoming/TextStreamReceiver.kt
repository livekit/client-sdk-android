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

import io.livekit.android.room.Room
import io.livekit.android.room.datastream.TextStreamInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Receiver for an incoming text stream.
 *
 * Chunks are decoded as UTF-8 strings. Provided to [TextStreamHandler] callbacks registered
 * through [Room].
 *
 * @see Room.registerTextStreamHandler
 */
class TextStreamReceiver(
    /** Metadata for this stream. */
    val info: TextStreamInfo,
    source: Channel<ByteArray>,
) : BaseStreamReceiver<String>(source) {
    override val flow: Flow<String> = source.receiveAsFlow()
        .map { bytes -> bytes.toString(Charsets.UTF_8) }
}
