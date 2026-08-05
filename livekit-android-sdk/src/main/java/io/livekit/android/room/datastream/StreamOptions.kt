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

package io.livekit.android.room.datastream

import io.livekit.android.room.participant.Participant
import java.util.UUID

data class StreamTextOptions(
    val topic: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val streamId: String = UUID.randomUUID().toString(),
    val destinationIdentities: List<Participant.Identity> = emptyList(),
    val operationType: TextStreamInfo.OperationType = TextStreamInfo.OperationType.CREATE,
    val version: Int = 0,
    val attachedStreamIds: List<String> = emptyList(),
    val replyToStreamId: String? = null,
    /**
     * The total exact size in bytes when encoded to UTF-8, if known.
     *
     * Ignored when opening an incremental stream with
     * [io.livekit.android.room.datastream.outgoing.OutgoingDataStreamManager.streamText], which is
     * always announced as unknown-length.
     */
    val totalSize: Long? = null,
    /**
     * Whether the payload may be compressed, when every recipient supports it.
     *
     * Only applies to whole-payload sends such as
     * [io.livekit.android.room.datastream.outgoing.OutgoingDataStreamManager.sendText]; incremental
     * streams are never compressed. Compression is additionally skipped unless every recipient
     * advertises [io.livekit.android.room.ClientCapability.COMPRESSION_DEFLATE_RAW], and is only
     * kept when it actually makes the payload smaller, so leaving this on is safe.
     */
    val compress: Boolean = true,
)

data class StreamBytesOptions(
    val topic: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val streamId: String = UUID.randomUUID().toString(),
    val destinationIdentities: List<Participant.Identity> = emptyList(),
    /**
     * The mime type of the stream data. Defaults to application/octet-stream
     */
    val mimeType: String = "application/octet-stream",
    /**
     * The name of the file being sent.
     */
    val name: String = "unknown",
    /**
     * The total exact size in bytes, if known.
     */
    val totalSize: Long? = null,
    /**
     * Whether the payload may be compressed, when every recipient supports it.
     *
     * @see StreamTextOptions.compress
     */
    val compress: Boolean = true,
)
