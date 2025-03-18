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

import livekit.LivekitModels
import livekit.LivekitModels.DataStream.ByteHeader
import livekit.LivekitModels.DataStream.Header
import livekit.LivekitModels.DataStream.TextHeader

sealed class StreamInfo(
    open val id: String,
    open val topic: String,
    open val timestampMs: Long,
    open val totalSize: Long?,
    open val attributes: Map<String, String>,
)

data class TextStreamInfo(
    override val id: String,
    override val topic: String,
    override val timestampMs: Long,
    override val totalSize: Long?,
    override val attributes: Map<String, String>,
    val operationType: OperationType,
    val version: Int,
    val replyToStreamId: String?,
    val attachedStreamIds: List<String>,
    val generated: Boolean,
) : StreamInfo(id, topic, timestampMs, totalSize, attributes) {
    constructor(header: Header, textHeader: TextHeader) : this(
        id = header.streamId,
        topic = header.topic,
        timestampMs = header.timestamp,
        totalSize = if (header.hasTotalLength()) {
            header.totalLength
        } else {
            null
        },
        attributes = header.attributesMap.toMap(),
        operationType = OperationType.fromProto(textHeader.operationType),
        version = textHeader.version,
        replyToStreamId = if (!textHeader.replyToStreamId.isNullOrEmpty()) {
            textHeader.replyToStreamId
        } else {
            null
        },
        attachedStreamIds = textHeader.attachedStreamIdsList ?: emptyList(),
        generated = textHeader.generated,
    )

    enum class OperationType {
        CREATE,
        UPDATE,
        DELETE,
        REACTION;

        /**
         * @throws IllegalArgumentException [operationType] is unrecognized
         */
        fun toProto(): LivekitModels.DataStream.OperationType {
            return when (this) {
                CREATE -> LivekitModels.DataStream.OperationType.CREATE
                UPDATE -> LivekitModels.DataStream.OperationType.UPDATE
                DELETE -> LivekitModels.DataStream.OperationType.DELETE
                REACTION -> LivekitModels.DataStream.OperationType.REACTION
            }
        }

        companion object {
            /**
             * @throws IllegalArgumentException [operationType] is unrecognized
             */
            fun fromProto(operationType: LivekitModels.DataStream.OperationType): OperationType {
                return when (operationType) {
                    LivekitModels.DataStream.OperationType.CREATE -> CREATE
                    LivekitModels.DataStream.OperationType.UPDATE -> UPDATE
                    LivekitModels.DataStream.OperationType.DELETE -> DELETE
                    LivekitModels.DataStream.OperationType.REACTION -> REACTION
                    LivekitModels.DataStream.OperationType.UNRECOGNIZED -> throw IllegalArgumentException("Unrecognized operation type!")
                }
            }
        }
    }
}

data class ByteStreamInfo(
    override val id: String,
    override val topic: String,
    override val timestampMs: Long,
    override val totalSize: Long?,
    override val attributes: Map<String, String>,
    val mimeType: String,
    val name: String?,
) : StreamInfo(id, topic, timestampMs, totalSize, attributes) {
    constructor(header: Header, byteHeader: ByteHeader) : this(
        id = header.streamId,
        topic = header.topic,
        timestampMs = header.timestamp,
        totalSize = if (header.hasTotalLength()) {
            header.totalLength
        } else {
            null
        },
        attributes = header.attributesMap.toMap(),
        mimeType = header.mimeType,
        name = byteHeader.name,
    )
}
