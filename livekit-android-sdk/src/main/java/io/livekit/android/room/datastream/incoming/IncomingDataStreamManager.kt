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

package io.livekit.android.room.datastream.incoming

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import io.livekit.android.room.datastream.ByteStreamInfo
import io.livekit.android.room.datastream.StreamException
import io.livekit.android.room.datastream.StreamInfo
import io.livekit.android.room.datastream.TextStreamInfo
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.LKLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import livekit.LivekitModels.DataStream
import java.util.Collections
import javax.inject.Inject

// Type-erased stream handler
private typealias AnyStreamHandler = (Channel<ByteArray>, Participant.Identity) -> Unit

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
    fun handleStreamHeader(header: DataStream.Header, fromIdentity: Participant.Identity)

    /**
     * @suppress
     */
    fun handleDataChunk(chunk: DataStream.Chunk)

    /**
     * @suppress
     */
    fun handleStreamTrailer(trailer: DataStream.Trailer)

    /**
     * @suppress
     */
    fun clearOpenStreams()
}

/**
 * @suppress
 */
class IncomingDataStreamManagerImpl @Inject constructor() : IncomingDataStreamManager {

    private data class Descriptor(
        val streamInfo: StreamInfo,
        /**
         * Measured by SystemClock.elapsedRealtime()
         */
        val openTime: Long,
        val channel: Channel<ByteArray>,
        var readLength: Long = 0,
    )

    private val openStreams = Collections.synchronizedMap(mutableMapOf<String, Descriptor>())
    private val textStreamHandlers = Collections.synchronizedMap(mutableMapOf<String, TextStreamHandler>())
    private val byteStreamHandlers = Collections.synchronizedMap(mutableMapOf<String, ByteStreamHandler>())

    /**
     * Registers a text stream handler for [topic]. Only one handler can be set for a particular topic at a time.
     *
     * @throws IllegalArgumentException if a topic is already set.
     */
    override fun registerTextStreamHandler(topic: String, handler: TextStreamHandler) {
        synchronized(textStreamHandlers) {
            if (textStreamHandlers.containsKey(topic)) {
                throw IllegalArgumentException("A text stream handler for topic $topic has already been set.")
            }

            textStreamHandlers[topic] = handler
        }
    }

    /**
     * Unregisters a previously registered text handler for [topic].
     */
    override fun unregisterTextStreamHandler(topic: String) {
        synchronized(textStreamHandlers) {
            textStreamHandlers.remove(topic)
        }
    }

    /**
     * Registers a byte stream handler for [topic]. Only one handler can be set for a particular topic at a time.
     *
     * @throws IllegalArgumentException if a topic is already set.
     */
    override fun registerByteStreamHandler(topic: String, handler: ByteStreamHandler) {
        synchronized(byteStreamHandlers) {
            if (byteStreamHandlers.containsKey(topic)) {
                throw IllegalArgumentException("A byte stream handler for topic $topic has already been set.")
            }

            byteStreamHandlers[topic] = handler
        }
    }

    /**
     * Unregisters a previously registered byte handler for [topic].
     */
    override fun unregisterByteStreamHandler(topic: String) {
        synchronized(byteStreamHandlers) {
            byteStreamHandlers.remove(topic)
        }
    }

    /**
     * @suppress
     */
    override fun handleStreamHeader(header: DataStream.Header, fromIdentity: Participant.Identity) {
        val info = streamInfoFromHeader(header) ?: return
        openStream(info, fromIdentity)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun openStream(info: StreamInfo, fromIdentity: Participant.Identity) {
        if (openStreams.containsKey(info.id)) {
            LKLog.w { "Stream already open for id ${info.id}" }
            return
        }

        val handler = getHandlerForInfo(info)
        val channel = createChannelForStreamReceiver()

        val descriptor = Descriptor(
            streamInfo = info,
            openTime = SystemClock.elapsedRealtime(),
            channel = channel,
        )

        openStreams[info.id] = descriptor
        channel.invokeOnClose { closeStream(id = info.id) }
        LKLog.d { "Opened stream ${info.id}" }

        try {
            handler.invoke(channel, fromIdentity)
        } catch (e: Exception) {
            LKLog.e(e) { "Unhandled exception when invoking stream handler!" }
        }
    }

    /**
     * @suppress
     */
    override fun handleDataChunk(chunk: DataStream.Chunk) {
        val content = chunk.content ?: return
        val descriptor = openStreams[chunk.streamId] ?: return
        val totalReadLength = descriptor.readLength + content.size()

        val totalLength = descriptor.streamInfo.totalSize
        if (totalLength != null) {
            if (totalReadLength > totalLength) {
                descriptor.channel.close(StreamException.LengthExceededException())
                return
            }
        }
        descriptor.readLength = totalReadLength
        descriptor.channel.trySend(content.toByteArray())
    }

    /**
     * @suppress
     */
    override fun handleStreamTrailer(trailer: DataStream.Trailer) {
        val descriptor = openStreams[trailer.streamId]
        if (descriptor == null) {
            LKLog.w { "Received trailer for unknown stream: ${trailer.streamId}" }
            return
        }

        val totalLength = descriptor.streamInfo.totalSize
        if (totalLength != null) {
            if (descriptor.readLength != totalLength) {
                descriptor.channel.close(StreamException.IncompleteException())
                return
            }
        }

        val reason = trailer.reason

        if (!reason.isNullOrEmpty()) {
            // A non-empty reason string indicates an error
            val exception = StreamException.AbnormalEndException(reason)
            descriptor.channel.close(exception)
            return
        }

        // Close successfully.
        descriptor.channel.close()
    }

    private fun closeStream(id: String) {
        synchronized(openStreams) {
            val descriptor = openStreams[id]
            if (descriptor == null) {
                LKLog.d { "Attempted to close stream $id, but no descriptor was found." }
                return
            }

            descriptor.channel.close()
            val openMillis = SystemClock.elapsedRealtime() - descriptor.openTime
            LKLog.d { "Closed stream $id, (open for ${openMillis}ms" }

            openStreams.remove(id)
        }
    }

    /**
     * @suppress
     */
    override fun clearOpenStreams() {
        synchronized(openStreams) {
            // Create a copy since closing the channel will also remove from openStreams,
            // causing a ConcurrentModificationException
            val descriptors = openStreams.values.toList()
            for (descriptor in descriptors) {
                descriptor.channel.close(StreamException.TerminatedException())
            }
            openStreams.clear()
        }
    }

    private fun getHandlerForInfo(info: StreamInfo): AnyStreamHandler {
        return when (info) {
            is ByteStreamInfo -> {
                val handler = byteStreamHandlers[info.topic]
                { channel, identity ->
                    if (handler == null) {
                        LKLog.w { "Received byte stream for topic \"${info.topic}\", but no handler was found. Ignoring." }
                    } else {
                        handler.invoke(ByteStreamReceiver(info, channel), identity)
                    }
                }
            }

            is TextStreamInfo -> {
                val handler = textStreamHandlers[info.topic]

                { channel, identity ->
                    if (handler == null) {
                        LKLog.w { "Received text stream for topic \"${info.topic}\", but no handler was found. Ignoring." }
                    } else {
                        handler.invoke(TextStreamReceiver(info, channel), identity)
                    }
                }
            }
        }
    }

    private fun streamInfoFromHeader(header: DataStream.Header): StreamInfo? {
        try {
            return when (header.contentHeaderCase) {
                DataStream.Header.ContentHeaderCase.TEXT_HEADER -> {
                    TextStreamInfo(header, header.textHeader)
                }

                DataStream.Header.ContentHeaderCase.BYTE_HEADER -> {
                    ByteStreamInfo(header, header.byteHeader)
                }

                DataStream.Header.ContentHeaderCase.CONTENTHEADER_NOT_SET,
                null,
                -> {
                    LKLog.i { "received header with non-set content header. streamId: ${header.streamId}, topic: ${header.topic}" }
                    null
                }
            }
        } catch (e: Exception) {
            LKLog.e(e) { "Exception when processing new stream header." }
            return null
        }
    }

    companion object {
        @VisibleForTesting
        fun createChannelForStreamReceiver() = Channel<ByteArray>(
            capacity = Int.MAX_VALUE,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
    }
}
