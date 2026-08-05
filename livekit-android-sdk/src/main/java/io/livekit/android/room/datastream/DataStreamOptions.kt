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

package io.livekit.android.room.datastream

/**
 * Room-wide data stream settings.
 *
 * @see io.livekit.android.RoomOptions.dataStreamOptions
 */
data class DataStreamOptions(
    /**
     * Maximum size, in bytes, of a reassembled incoming data stream payload.
     *
     * A stream whose payload would exceed this fails its reader rather than buffering without
     * bound, which keeps a misbehaving or malicious sender from growing memory regardless of the
     * length its header declared. Null uses the built-in default.
     */
    val maxPayloadSize: Long? = null,
)
