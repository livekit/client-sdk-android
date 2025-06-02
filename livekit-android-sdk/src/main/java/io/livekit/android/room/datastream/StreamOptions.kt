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

package io.livekit.android.room.datastream

import io.livekit.android.room.participant.Participant
import livekit.LivekitModels
import java.util.UUID

interface StreamOptions {
    val topic: String?
    val attributes: Map<String, String>?
    val totalLength: Long?
    val mimeType: String?
    val encryptionType: LivekitModels.Encryption.Type?
    val destinationIdentities: List<Participant.Identity>
}

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
     */
    val totalSize: Long? = null,
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
    val name: String,
    /**
     * The total exact size in bytes, if known.
     */
    val totalSize: Long? = null,
)
