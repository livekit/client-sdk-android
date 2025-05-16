/*
 * Copyright 2023-2025 LiveKit, Inc.
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

import com.google.protobuf.ByteString
import io.livekit.android.room.RTCEngine
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.assert.assertIsClass
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.MockPeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import livekit.LivekitModels.DataPacket
import livekit.LivekitModels.DataStream
import livekit.LivekitModels.DataStream.OperationType
import livekit.LivekitModels.DataStream.TextHeader
import livekit.org.webrtc.DataChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class RoomIncomingDataStreamMockE2ETest : MockE2ETest() {
    @Test
    fun dataStream() = runTest {
        connect()
        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        val subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)

        val scope = CoroutineScope(currentCoroutineContext())
        val collectedData = mutableListOf<ByteArray>()
        var finished = false
        room.registerByteStreamHandler("topic") { reader, _ ->
            scope.launch {
                reader.flow.collect {
                    collectedData.add(it)
                }
                finished = true
            }
        }

        subDataChannel.observer?.onMessage(createStreamHeader().wrap())
        subDataChannel.observer?.onMessage(createStreamChunk(0, ByteArray(1) { 1 }).wrap())
        subDataChannel.observer?.onMessage(createStreamTrailer().wrap())

        assertTrue(finished)
        assertEquals(1, collectedData.size)
        assertEquals(1, collectedData[0][0].toInt())
    }

    @Test
    fun textStream() = runTest {
        connect()
        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        val subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)

        val scope = CoroutineScope(currentCoroutineContext())
        val collectedData = mutableListOf<String>()
        var finished = false
        room.registerTextStreamHandler("topic") { reader, _ ->
            scope.launch {
                reader.flow.collect {
                    collectedData.add(it)
                }
                finished = true
            }
        }

        val textStreamHeader = with(createStreamHeader().toBuilder()) {
            streamHeader = with(streamHeader.toBuilder()) {
                clearByteHeader()
                textHeader = with(TextHeader.newBuilder()) {
                    operationType = OperationType.CREATE
                    generated = false
                    build()
                }
                build()
            }
            build()
        }
        subDataChannel.observer?.onMessage(textStreamHeader.wrap())
        subDataChannel.observer?.onMessage(createStreamChunk(0, "hello".toByteArray()).wrap())
        subDataChannel.observer?.onMessage(createStreamTrailer().wrap())

        assertTrue(finished)
        assertEquals(1, collectedData.size)
        assertEquals("hello", collectedData[0])
    }

    @Test
    fun dataStreamTerminated() = runTest {
        connect()
        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        val subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)

        val scope = CoroutineScope(currentCoroutineContext())
        var finished = false
        var threwOnce = false
        room.registerByteStreamHandler("topic") { reader, _ ->
            scope.launch {
                reader.flow
                    .catch {
                        assertIsClass(StreamException.AbnormalEndException::class.java, it)
                        threwOnce = true
                    }
                    .collect {
                    }
                finished = true
            }
        }

        subDataChannel.observer?.onMessage(createStreamHeader().wrap())

        val abnormalEnd = with(DataPacket.newBuilder()) {
            streamTrailer = with(DataStream.Trailer.newBuilder()) {
                streamId = "streamId"
                reason = "reason"
                build()
            }
            build()
        }
        subDataChannel.observer?.onMessage(abnormalEnd.wrap())

        assertTrue(finished)
        assertTrue(threwOnce)
    }

    @Test
    fun dataStreamLengthExceeded() = runTest {
        connect()
        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        val subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)

        val scope = CoroutineScope(currentCoroutineContext())
        var finished = false
        var threwOnce = false
        room.registerByteStreamHandler("topic") { reader, _ ->
            scope.launch {
                reader.flow
                    .catch {
                        assertIsClass(StreamException.LengthExceededException::class.java, it)
                        threwOnce = true
                    }
                    .collect {
                    }
                finished = true
            }
        }
        val header = with(createStreamHeader().toBuilder()) {
            streamHeader = with(streamHeader.toBuilder()) {
                totalLength = 1
                build()
            }
            build()
        }
        subDataChannel.observer?.onMessage(header.wrap())
        subDataChannel.observer?.onMessage(createStreamChunk(0, ByteArray(2) { 1 }).wrap())
        subDataChannel.observer?.onMessage(createStreamTrailer().wrap())

        assertTrue(finished)
        assertTrue(threwOnce)
    }

    @Test
    fun dataStreamIncomplete() = runTest {
        connect()
        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        val subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)

        val scope = CoroutineScope(currentCoroutineContext())
        var finished = false
        var threwOnce = false
        room.registerByteStreamHandler("topic") { reader, _ ->
            scope.launch {
                reader.flow
                    .catch {
                        assertIsClass(StreamException.IncompleteException::class.java, it)
                        threwOnce = true
                    }
                    .collect {
                    }
                finished = true
            }
        }
        val header = with(createStreamHeader().toBuilder()) {
            streamHeader = with(streamHeader.toBuilder()) {
                totalLength = 2
                build()
            }
            build()
        }
        subDataChannel.observer?.onMessage(header.wrap())
        subDataChannel.observer?.onMessage(createStreamChunk(0, ByteArray(1) { 1 }).wrap())
        subDataChannel.observer?.onMessage(createStreamTrailer().wrap())

        assertTrue(finished)
        assertTrue(threwOnce)
    }

    private fun DataPacket.wrap() = DataChannel.Buffer(
        ByteBuffer.wrap(this.toByteArray()),
        true,
    )

    fun createStreamHeader() = with(DataPacket.newBuilder()) {
        streamHeader = with(DataStream.Header.newBuilder()) {
            streamId = "streamId"
            topic = "topic"
            timestamp = 0L
            clearTotalLength()
            mimeType = "mime"

            byteHeader = with(DataStream.ByteHeader.newBuilder()) {
                name = "name"
                build()
            }
            build()
        }
        build()
    }

    fun createStreamChunk(index: Int, bytes: ByteArray) = with(DataPacket.newBuilder()) {
        streamChunk = with(DataStream.Chunk.newBuilder()) {
            streamId = "streamId"
            chunkIndex = index.toLong()
            content = ByteString.copyFrom(bytes)
            build()
        }
        build()
    }

    fun createStreamTrailer() = with(DataPacket.newBuilder()) {
        streamTrailer = with(DataStream.Trailer.newBuilder()) {
            streamId = "streamId"
            build()
        }
        build()
    }
}
