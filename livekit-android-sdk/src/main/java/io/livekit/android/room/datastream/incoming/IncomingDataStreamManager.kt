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

import androidx.annotation.VisibleForTesting
import io.livekit.android.room.datastream.DataStreams
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import livekit.LivekitModels
import livekit.LivekitModels.DataStream
import javax.inject.Inject

typealias ByteStreamHandler = (reader: ByteStreamReceiver, fromIdentity: Participant.Identity) -> Unit
typealias TextStreamHandler = (reader: TextStreamReceiver, fromIdentity: Participant.Identity) -> Unit

interface IncomingDataStreamManager {

    /**
     * Registers a text stream handler for [topic]. Only one handler can be set for a particular topic at a time.
     *
     * @throws IllegalArgumentException if a topic is already set.
     */
    fun registerTextStreamHandler(topic: String, handler: TextStreamHandler)

    /**
     * Unregisters a previously registered text handler for [topic].
     */
    fun unregisterTextStreamHandler(topic: String)

    /**
     * Registers a byte stream handler for [topic]. Only one handler can be set for a particular topic at a time.
     *
     * @throws IllegalArgumentException if a topic is already set.
     */
    fun registerByteStreamHandler(topic: String, handler: ByteStreamHandler)

    /**
     * Unregisters a previously registered byte handler for [topic].
     */
    fun unregisterByteStreamHandler(topic: String)

    /**
     * @suppress
     */
    fun handleStreamHeader(header: DataStream.Header, fromIdentity: Participant.Identity, encryptionType: LivekitModels.Encryption.Type)

    /**
     * @suppress
     */
    fun handleDataChunk(chunk: DataStream.Chunk, encryptionType: LivekitModels.Encryption.Type)

    /**
     * @suppress
     */
    fun handleStreamTrailer(trailer: DataStream.Trailer, encryptionType: LivekitModels.Encryption.Type)

    /**
     * @suppress
     */
    fun clearOpenStreams()
}

/**
 * Adapts [IncomingDataStreamManager] onto [DataStreams], which owns the actual implementation.
 *
 * @suppress
 */
class IncomingDataStreamManagerImpl @Inject constructor(
    private val dataStreams: DataStreams,
) : IncomingDataStreamManager {

    override fun registerTextStreamHandler(topic: String, handler: TextStreamHandler) {
        dataStreams.registerTextStreamHandler(topic, handler)
    }

    override fun unregisterTextStreamHandler(topic: String) {
        dataStreams.unregisterTextStreamHandler(topic)
    }

    override fun registerByteStreamHandler(topic: String, handler: ByteStreamHandler) {
        dataStreams.registerByteStreamHandler(topic, handler)
    }

    override fun unregisterByteStreamHandler(topic: String) {
        dataStreams.unregisterByteStreamHandler(topic)
    }

    /**
     * @suppress
     */
    override fun handleStreamHeader(
        header: DataStream.Header,
        fromIdentity: Participant.Identity,
        encryptionType: LivekitModels.Encryption.Type,
    ) {
        dataStreams.handleIncoming(
            LivekitModels.DataPacket.newBuilder()
                .setParticipantIdentity(fromIdentity.value)
                .setStreamHeader(header)
                .build(),
        )
    }

    /**
     * @suppress
     */
    override fun handleDataChunk(chunk: DataStream.Chunk, encryptionType: LivekitModels.Encryption.Type) {
        // No sender identity: chunks are routed by stream id, and the identity recorded against an
        // open stream comes from its header.
        dataStreams.handleIncoming(
            LivekitModels.DataPacket.newBuilder()
                .setStreamChunk(chunk)
                .build(),
        )
    }

    /**
     * @suppress
     */
    override fun handleStreamTrailer(trailer: DataStream.Trailer, encryptionType: LivekitModels.Encryption.Type) {
        dataStreams.handleIncoming(
            LivekitModels.DataPacket.newBuilder()
                .setStreamTrailer(trailer)
                .build(),
        )
    }

    /**
     * @suppress
     */
    override fun clearOpenStreams() {
        dataStreams.abortAllStreams()
    }

    companion object {
        /**
         * @suppress
         */
        @VisibleForTesting
        fun createChannelForStreamReceiver() = Channel<ByteArray>(
            capacity = Int.MAX_VALUE,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
    }
}
