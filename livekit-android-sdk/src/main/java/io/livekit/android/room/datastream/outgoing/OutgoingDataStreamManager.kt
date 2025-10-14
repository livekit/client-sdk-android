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

package io.livekit.android.room.datastream.outgoing

import androidx.annotation.CheckResult
import com.google.protobuf.ByteString
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.datastream.ByteStreamInfo
import io.livekit.android.room.datastream.StreamBytesOptions
import io.livekit.android.room.datastream.StreamException
import io.livekit.android.room.datastream.StreamInfo
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.datastream.TextStreamInfo
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.LKLog
import livekit.LivekitModels
import livekit.LivekitModels.DataPacket
import livekit.LivekitModels.DataStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

interface OutgoingDataStreamManager {
    /**
     * Start sending a stream of text. Call [TextStreamSender.close] when finished sending.
     *
     * @see [TextStreamSender.write]
     * @throws StreamException if the stream failed to open.
     */
    suspend fun streamText(options: StreamTextOptions = StreamTextOptions()): TextStreamSender

    /**
     * Start sending a stream of bytes. Call [ByteStreamSender.close] when finished sending.
     *
     * Extension functions are available for sending bytes from sources such as [InputStream] or [File].
     *
     * @see [ByteStreamSender.write]
     * @see [ByteStreamSender.writeFile]
     * @throws StreamException if the stream failed to open.
     */
    suspend fun streamBytes(options: StreamBytesOptions): ByteStreamSender

    /**
     * Send text through a data stream.
     */
    @CheckResult
    suspend fun sendText(text: String, options: StreamTextOptions = StreamTextOptions()): Result<TextStreamInfo> {
        try {
            val sender = streamText(options)
            val result = sender.write(text)

            if (result.isFailure) {
                val exception = result.exceptionOrNull() ?: Exception("Unknown error.")
                sender.close(exception.message)
                return Result.failure(exception)
            } else {
                sender.close()
                return Result.success(sender.info)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Send a file through a data stream.
     */
    @CheckResult
    suspend fun sendFile(file: File, options: StreamBytesOptions = StreamBytesOptions()): Result<ByteStreamInfo> {
        try {
            val sender = streamBytes(options)
            val result = sender.writeFile(file)

            if (result.isFailure) {
                val exception = result.exceptionOrNull() ?: Exception("Unknown error.")
                sender.close(exception.message)
                return Result.failure(exception)
            } else {
                sender.close()
                return Result.success(sender.info)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
}

/**
 * @suppress
 */
class OutgoingDataStreamManagerImpl
@Inject
constructor(
    val engine: RTCEngine,
) : OutgoingDataStreamManager {

    private data class Descriptor(
        val info: StreamInfo,
        val destinationIdentityStrings: List<String>,
        var writtenLength: Long = 0L,
        val nextChunkIndex: AtomicLong = AtomicLong(0),
    )

    private val openStreams = Collections.synchronizedMap(mutableMapOf<String, Descriptor>())

    @CheckResult
    private suspend fun openStream(
        info: StreamInfo,
        destinationIdentities: List<Participant.Identity> = emptyList(),
    ): Result<Unit> {
        if (openStreams.containsKey(info.id)) {
            throw StreamException.AlreadyOpenedException()
        }

        val destinationIdentityStrings = destinationIdentities.map { it.value }
        val headerPacket = with(DataPacket.newBuilder()) {
            addAllDestinationIdentities(destinationIdentityStrings)
            kind = DataPacket.Kind.RELIABLE
            streamHeader = with(DataStream.Header.newBuilder()) {
                this.streamId = info.id
                this.topic = info.topic
                this.timestamp = info.timestampMs
                this.putAllAttributes(info.attributes)

                info.totalSize?.let {
                    this.totalLength = it
                }

                when (info) {
                    is ByteStreamInfo -> {
                        this.mimeType = info.mimeType
                        this.byteHeader = with(DataStream.ByteHeader.newBuilder()) {
                            this.name = name
                            build()
                        }
                    }

                    is TextStreamInfo -> {
                        textHeader = with(DataStream.TextHeader.newBuilder()) {
                            this.operationType = info.operationType.toProto()
                            this.version = info.version
                            if (info.replyToStreamId != null) {
                                this.replyToStreamId = info.replyToStreamId
                            }
                            this.addAllAttachedStreamIds(info.attachedStreamIds)
                            this.generated = info.generated
                            build()
                        }
                    }
                }
                build()
            }
            build()
        }

        val result = engine.sendData(headerPacket)
        if (result.isFailure) {
            return result
        }

        val descriptor = Descriptor(info, destinationIdentityStrings)
        openStreams[info.id] = descriptor

        LKLog.d { "Opened send stream ${info.id}" }
        return Result.success(Unit)
    }

    @CheckResult
    private suspend fun sendChunk(streamId: String, dataChunk: ByteArray): Result<Unit> {
        val descriptor = openStreams[streamId] ?: throw StreamException.UnknownStreamException()
        val nextChunkIndex = descriptor.nextChunkIndex.getAndIncrement()

        val chunkPacket = with(DataPacket.newBuilder()) {
            addAllDestinationIdentities(descriptor.destinationIdentityStrings)
            kind = DataPacket.Kind.RELIABLE
            streamChunk = with(DataStream.Chunk.newBuilder()) {
                this.streamId = streamId
                this.content = ByteString.copyFrom(dataChunk)
                this.chunkIndex = nextChunkIndex
                build()
            }
            build()
        }

        engine.waitForBufferStatusLow(DataPacket.Kind.RELIABLE)
        return engine.sendData(chunkPacket)
    }

    private suspend fun closeStream(streamId: String, reason: String? = null) {
        val descriptor = openStreams[streamId] ?: throw StreamException.UnknownStreamException()

        val trailerPacket = with(DataPacket.newBuilder()) {
            addAllDestinationIdentities(descriptor.destinationIdentityStrings)
            kind = DataPacket.Kind.RELIABLE
            streamTrailer = with(DataStream.Trailer.newBuilder()) {
                this.streamId = streamId
                if (reason != null) {
                    this.reason = reason
                }
                build()
            }
            build()
        }

        engine.waitForBufferStatusLow(DataPacket.Kind.RELIABLE)
        val result = engine.sendData(trailerPacket)

        if (result.isFailure) {
            // Log close failure only for now.
            LKLog.w(result.exceptionOrNull()) { "Error when closing stream!" }
        }

        openStreams.remove(streamId)
        LKLog.d { "Closed send stream $streamId" }
    }

    override suspend fun streamText(options: StreamTextOptions): TextStreamSender {
        val streamInfo = TextStreamInfo(
            id = options.streamId,
            topic = options.topic,
            timestampMs = Date().time,
            totalSize = options.totalSize,
            attributes = options.attributes,
            operationType = options.operationType,
            version = options.version,
            replyToStreamId = options.replyToStreamId,
            attachedStreamIds = options.attachedStreamIds,
            generated = false,
            encryptionType = if (engine.e2EEManager?.isDataChannelEncryptionEnabled() ?: false) {
                LivekitModels.Encryption.Type.GCM
            } else {
                LivekitModels.Encryption.Type.NONE
            },
        )

        val streamId = options.streamId
        val result = openStream(streamInfo, options.destinationIdentities)

        if (result.isFailure) {
            throw result.exceptionOrNull() ?: StreamException.TerminatedException("Unknown failure when opening the stream!")
        }

        val destination = ManagerStreamDestination<String>(streamId)
        return TextStreamSender(
            streamInfo,
            destination,
        )
    }

    override suspend fun streamBytes(options: StreamBytesOptions): ByteStreamSender {
        val streamInfo = ByteStreamInfo(
            id = options.streamId,
            topic = options.topic,
            timestampMs = Date().time,
            totalSize = options.totalSize,
            attributes = options.attributes,
            mimeType = options.mimeType,
            name = options.name,
            encryptionType = if (engine.e2EEManager?.isDataChannelEncryptionEnabled() ?: false) {
                LivekitModels.Encryption.Type.GCM
            } else {
                LivekitModels.Encryption.Type.NONE
            },
        )

        val streamId = options.streamId
        val result = openStream(streamInfo, options.destinationIdentities)

        if (result.isFailure) {
            throw result.exceptionOrNull() ?: StreamException.TerminatedException("Unknown failure when opening the stream!")
        }
        val destination = ManagerStreamDestination<ByteArray>(streamId)
        return ByteStreamSender(
            streamInfo,
            destination,
        )
    }

    private inner class ManagerStreamDestination<T>(val streamId: String) : StreamDestination<T> {
        override val isOpen: Boolean
            get() = openStreams.contains(streamId)

        override suspend fun write(data: T, chunker: DataChunker<T>): Result<Unit> {
            if (!isOpen) {
                return Result.failure(StreamException.TerminatedException("Stream is closed!"))
            }
            val chunks = chunker.invoke(data, RTCEngine.MAX_DATA_PACKET_SIZE)

            for (chunk in chunks) {
                val result = sendChunk(streamId, chunk)
                if (result.isFailure) {
                    return result
                }
            }
            return Result.success(Unit)
        }

        override suspend fun close(reason: String?) {
            closeStream(streamId, reason)
        }
    }
}
