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
import io.livekit.android.room.participant.Participant
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.TestData
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitModels
import livekit.LivekitRtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomOutgoingDataStreamMockE2ETest : MockE2ETest() {

    private lateinit var pubDataChannel: MockDataChannel

    override suspend fun connect(joinResponse: LivekitRtc.SignalResponse) {
        super.connect(joinResponse)

        val pubPeerConnection = component.rtcEngine().getPublisherPeerConnection() as MockPeerConnection
        pubDataChannel = pubPeerConnection.dataChannels[RTCEngine.RELIABLE_DATA_CHANNEL_LABEL] as MockDataChannel
    }

    @Test
    fun dataStream() = runTest {
        connect()

        // Remote participant to send data to
        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        val bytesToStream = ByteArray(100)
        for (i in bytesToStream.indices) {
            bytesToStream[i] = i.toByte()
        }
        val sender = room.localParticipant.streamBytes(
            StreamBytesOptions(
                topic = "topic",
                attributes = mapOf("hello" to "world"),
                streamId = "stream_id",
                destinationIdentities = listOf(Participant.Identity(TestData.REMOTE_PARTICIPANT.identity)),
                name = "stream_name",
                totalSize = bytesToStream.size.toLong(),
            ),
        )
        assertTrue(sender.write(bytesToStream).isSuccess)
        sender.close()
        assertFalse(sender.isOpen)

        val buffers = pubDataChannel.sentBuffers

        println(buffers)
        assertEquals(3, buffers.size)

        val headerPacket = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))
        assertTrue(headerPacket.hasStreamHeader())

        with(headerPacket.streamHeader) {
            assertTrue(hasByteHeader())
        }

        val payloadPacket = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[1].data))
        assertTrue(payloadPacket.hasStreamChunk())
        with(payloadPacket.streamChunk) {
            assertEquals(100, content.size())
        }

        val trailerPacket = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[2].data))
        assertTrue(trailerPacket.hasStreamTrailer())
        with(trailerPacket.streamTrailer) {
            assertTrue(reason.isNullOrEmpty())
        }
    }

    @Test
    fun textStream() = runTest {
        connect()

        // Remote participant to send data to
        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        val text = "test_text"
        val sender = room.localParticipant.streamText(
            StreamTextOptions(
                topic = "topic",
                attributes = mapOf("hello" to "world"),
                streamId = "stream_id",
                destinationIdentities = listOf(Participant.Identity(TestData.REMOTE_PARTICIPANT.identity)),
                operationType = TextStreamInfo.OperationType.CREATE,
                version = 0,
                attachedStreamIds = emptyList(),
                replyToStreamId = null,
                totalSize = 3,
            ),
        )
        assertTrue(sender.write(text).isSuccess)
        sender.close()

        assertFalse(sender.isOpen)

        val buffers = pubDataChannel.sentBuffers

        println(buffers)
        assertEquals(3, buffers.size)

        val headerPacket = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))
        assertTrue(headerPacket.hasStreamHeader())

        with(headerPacket.streamHeader) {
            assertTrue(hasTextHeader())
        }

        val payloadPacket = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[1].data))
        assertTrue(payloadPacket.hasStreamChunk())
        with(payloadPacket.streamChunk) {
            assertEquals(text, content.toStringUtf8())
        }

        val trailerPacket = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[2].data))
        assertTrue(trailerPacket.hasStreamTrailer())
        with(trailerPacket.streamTrailer) {
            assertTrue(reason.isNullOrEmpty())
        }
    }
}
