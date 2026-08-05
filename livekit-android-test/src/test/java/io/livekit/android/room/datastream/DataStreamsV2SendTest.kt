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

import io.livekit.android.memory.CloseableManager
import io.livekit.android.room.ClientCapability
import io.livekit.android.room.ClientProtocolVersion
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.participant.Participant
import io.livekit.android.test.BaseTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitModels
import livekit.LivekitModels.DataPacket
import livekit.LivekitModels.DataStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.stub
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The v2 send path, end to end through [DataStreams] and out to a stubbed engine.
 *
 * These are the interop-critical cases: whether a payload goes out as one inline packet or as
 * header/chunks/trailer, and whether it is compressed, is decided by the core from what we tell it
 * about each recipient. That makes this as much a test of our registry wiring and options mapping
 * as of the framing itself -- if we report capabilities wrongly, the core silently picks a framing
 * a peer cannot read, and nothing here fails locally.
 *
 * Cases follow the matrix in rust-sdks/DATA_STREAMS_SPEC.md ("Minimum required test cases").
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DataStreamsV2SendTest : BaseTest() {

    @Mock
    lateinit var engine: RTCEngine

    private lateinit var dataStreams: DataStreams
    private val sentPackets = CopyOnWriteArrayList<DataPacket>()

    /** identity -> (clientProtocol, capabilities) */
    private var remotes: Map<String, Pair<Int, List<ClientCapability>>> = emptyMap()

    private companion object {
        const val TOPIC = "topic"

        val PRE_V2 = mapOf(
            "alice" to (ClientProtocolVersion.DEFAULT.value to emptyList<ClientCapability>()),
            "bob" to (ClientProtocolVersion.DEFAULT.value to emptyList<ClientCapability>()),
            "jim" to (ClientProtocolVersion.DATA_STREAM_RPC.value to emptyList<ClientCapability>()),
        )

        val ALL_V2 = mapOf(
            "alice" to (ClientProtocolVersion.DATA_STREAM_V2.value to listOf(ClientCapability.COMPRESSION_DEFLATE_RAW)),
            "bob" to (ClientProtocolVersion.DATA_STREAM_V2.value to listOf(ClientCapability.COMPRESSION_DEFLATE_RAW)),
            "noCompression" to (ClientProtocolVersion.DATA_STREAM_V2.value to emptyList<ClientCapability>()),
        )

        val MIXED = PRE_V2 + ALL_V2

        /** Compresses well, and short enough that the inline header still fits the MTU budget. */
        const val COMPRESSIBLE = "hello hello compressible world"
    }

    @Before
    fun setup() {
        engine.stub {
            onBlocking { sendData(any()) } doAnswer { invocation ->
                sentPackets.add(invocation.arguments[0] as DataPacket)
                Result.success(Unit)
            }
            onBlocking { waitForBufferStatusLow(any()) } doReturn Unit
            on { e2EEManager } doReturn null
        }
        dataStreams = DataStreams(engine = engine, closeableManager = CloseableManager())
        dataStreams.remoteIdentities = { remotes.keys.map { Participant.Identity(it) } }
        dataStreams.remoteClientProtocol = { id -> remotes[id.value]?.first ?: ClientProtocolVersion.DEFAULT.value }
        dataStreams.remoteCapabilities = { id -> remotes[id.value]?.second ?: emptyList() }
    }

    @After
    fun tearDown() {
        dataStreams.close()
    }

    // region Helpers

    private fun awaitPackets(count: Int): List<DataPacket> {
        awaitCondition(message = "Expected $count packet(s), saw ${sentPackets.size}") {
            sentPackets.size >= count
        }
        // Give any extra packets a chance to show up, so "exactly N" assertions are meaningful.
        awaitStable { sentPackets.size }
        return sentPackets.toList()
    }

    private fun destinations(vararg identities: String) = identities.map { Participant.Identity(it) }

    private val DataPacket.header: DataStream.Header get() = streamHeader

    // endregion

    // region A room where every recipient predates v2

    @Test
    fun preV2RoomSendsLegacyThreePackets() = runTest {
        remotes = PRE_V2

        dataStreams.sendText("hello world", StreamTextOptions(topic = TOPIC))

        val packets = awaitPackets(3)
        assertEquals(3, packets.size)
        assertTrue(packets[0].hasStreamHeader())
        assertTrue(packets[0].header.hasTextHeader())
        assertTrue(packets[1].hasStreamChunk())
        assertTrue(packets[2].hasStreamTrailer())

        assertEquals(DataStream.CompressionType.NONE, packets[0].header.compression)
        assertFalse("a pre-v2 recipient must never be sent inline content", packets[0].header.hasInlineContent())
        assertEquals("hello world", packets[1].streamChunk.content.toStringUtf8())
    }

    @Test
    fun preV2RoomSendsBytesUncompressed() = runTest {
        remotes = PRE_V2

        dataStreams.sendBytes(byteArrayOf(0, 1, 2, 3), StreamBytesOptions(topic = TOPIC))

        val packets = awaitPackets(3)
        assertEquals(3, packets.size)
        assertTrue(packets[0].header.hasByteHeader())
        assertEquals(DataStream.CompressionType.NONE, packets[0].header.compression)
        assertFalse(packets[0].header.hasInlineContent())
        assertArrayEqualsBytes(byteArrayOf(0, 1, 2, 3), packets[1].streamChunk.content.toByteArray())
    }

    // endregion

    // region A room where every recipient speaks v2

    @Test
    fun v2RoomSendsCompressibleTextAsOneCompressedPacket() = runTest {
        remotes = ALL_V2

        dataStreams.sendText(
            COMPRESSIBLE,
            StreamTextOptions(topic = TOPIC, destinationIdentities = destinations("alice", "bob")),
        )

        val packets = awaitPackets(1)
        assertEquals("inline sends are a single packet", 1, packets.size)
        assertEquals(DataStream.CompressionType.DEFLATE_RAW, packets[0].header.compression)
        assertTrue(packets[0].header.hasInlineContent())
        assertFalse(
            "inline content should be compressed, not the raw text",
            packets[0].header.inlineContent.toStringUtf8() == COMPRESSIBLE,
        )
    }

    @Test
    fun v2RoomSendsIncompressibleTextInlineButRaw() = runTest {
        remotes = ALL_V2

        // Too short for deflate to win; the core keeps the raw bytes rather than growing them.
        dataStreams.sendText(
            "short",
            StreamTextOptions(topic = TOPIC, destinationIdentities = destinations("alice", "bob")),
        )

        val packets = awaitPackets(1)
        assertEquals(1, packets.size)
        assertEquals(DataStream.CompressionType.NONE, packets[0].header.compression)
        assertEquals("short", packets[0].header.inlineContent.toStringUtf8())
    }

    /**
     * The two v2 features are gated independently: inline on the protocol version, compression on
     * the capability. A recipient that advertises v2 but no codec still gets the single packet.
     */
    @Test
    fun recipientWithoutCompressionCapabilityStillGetsInline() = runTest {
        remotes = ALL_V2

        dataStreams.sendText(
            COMPRESSIBLE,
            StreamTextOptions(topic = TOPIC, destinationIdentities = destinations("noCompression")),
        )

        val packets = awaitPackets(1)
        assertEquals(1, packets.size)
        assertEquals(DataStream.CompressionType.NONE, packets[0].header.compression)
        assertEquals(COMPRESSIBLE, packets[0].header.inlineContent.toStringUtf8())
    }

    @Test
    fun compressOptOutStillSendsInline() = runTest {
        remotes = ALL_V2

        dataStreams.sendText(
            COMPRESSIBLE,
            StreamTextOptions(
                topic = TOPIC,
                destinationIdentities = destinations("alice", "bob"),
                compress = false,
            ),
        )

        val packets = awaitPackets(1)
        assertEquals(1, packets.size)
        assertEquals(DataStream.CompressionType.NONE, packets[0].header.compression)
        assertEquals(COMPRESSIBLE, packets[0].header.inlineContent.toStringUtf8())
    }

    @Test
    fun v2RoomSendsShortBytesInline() = runTest {
        remotes = ALL_V2

        dataStreams.sendBytes(
            byteArrayOf(0, 1, 2, 3),
            StreamBytesOptions(topic = TOPIC, destinationIdentities = destinations("alice", "bob")),
        )

        val packets = awaitPackets(1)
        assertEquals(1, packets.size)
        assertTrue(packets[0].header.hasByteHeader())
        assertEquals(DataStream.CompressionType.NONE, packets[0].header.compression)
        assertArrayEqualsBytes(byteArrayOf(0, 1, 2, 3), packets[0].header.inlineContent.toByteArray())
    }

    /**
     * A payload too big to fit a header packet falls back to chunks, but stays compressed.
     */
    @Test
    fun largePayloadFallsBackToCompressedChunks() = runTest {
        remotes = ALL_V2
        // Only somewhat compressible: repetitive text would deflate small enough to still fit
        // inline, which is a different case (and covered above). Seeded, so the size is stable.
        val random = java.util.Random(1234)
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val payload = buildString {
            repeat(50) {
                append("hello world")
                repeat(1_000) { append(alphabet[random.nextInt(alphabet.size)]) }
            }
        }

        dataStreams.sendText(
            payload,
            StreamTextOptions(topic = TOPIC, destinationIdentities = destinations("alice", "bob")),
        )

        val packets = awaitPackets(3)
        assertTrue("expected a chunked send, got ${packets.size} packet(s)", packets.size > 1)
        assertEquals(DataStream.CompressionType.DEFLATE_RAW, packets[0].header.compression)
        assertFalse(packets[0].header.hasInlineContent())
        assertTrue(packets.last().hasStreamTrailer())

        val compressedSize = packets.filter { it.hasStreamChunk() }.sumOf { it.streamChunk.content.size() }
        assertTrue(
            "compressed chunks ($compressedSize) should be smaller than the payload (${payload.length})",
            compressedSize < payload.length,
        )
    }

    // endregion

    // region Incremental writers

    /**
     * Incremental writers are never inlined or compressed: the payload is not known up front, and
     * the core cannot flush a deflate stream mid-write.
     */
    @Test
    fun streamTextIsNeverInlineOrCompressed() = runTest {
        remotes = ALL_V2

        val sender = dataStreams.streamText(
            StreamTextOptions(topic = TOPIC, destinationIdentities = destinations("alice", "bob")),
        )
        awaitPackets(1)
        assertEquals(DataStream.CompressionType.NONE, sentPackets[0].header.compression)
        assertFalse(sentPackets[0].header.hasInlineContent())

        assertTrue(sender.write(COMPRESSIBLE).isSuccess)
        val afterWrite = awaitPackets(2)
        assertTrue(afterWrite[1].hasStreamChunk())
        assertEquals(COMPRESSIBLE, afterWrite[1].streamChunk.content.toStringUtf8())

        sender.close()
        val afterClose = awaitPackets(3)
        assertTrue(afterClose[2].hasStreamTrailer())
        assertTrue(afterClose[2].streamTrailer.reason.isEmpty())
    }

    @Test
    fun streamBytesIsNeverInlineOrCompressed() = runTest {
        remotes = ALL_V2

        val sender = dataStreams.streamBytes(
            StreamBytesOptions(topic = TOPIC, destinationIdentities = destinations("alice", "bob")),
        )
        assertTrue(sender.write(byteArrayOf(0, 1, 2, 3)).isSuccess)
        sender.close()

        val packets = awaitPackets(3)
        assertEquals(3, packets.size)
        assertTrue(packets[0].header.hasByteHeader())
        assertEquals(DataStream.CompressionType.NONE, packets[0].header.compression)
        assertArrayEqualsBytes(byteArrayOf(0, 1, 2, 3), packets[1].streamChunk.content.toByteArray())
    }

    /** A non-null close reason travels in the trailer, which the receiver reads as an error. */
    @Test
    fun closeWithReasonSetsTrailerReason() = runTest {
        remotes = ALL_V2

        val sender = dataStreams.streamText(StreamTextOptions(topic = TOPIC))
        sender.close(reason = "because")

        val packets = awaitPackets(2)
        val trailer = packets.first { it.hasStreamTrailer() }.streamTrailer
        assertEquals("because", trailer.reason)
    }

    // endregion

    // region Mixed rooms

    /**
     * Eligibility is unanimous across recipients. One pre-v2 participant in a broadcast is enough
     * to drop the whole send back to a framing everyone understands.
     */
    @Test
    fun broadcastToMixedRoomFallsBackToLegacy() = runTest {
        remotes = MIXED

        dataStreams.sendText(COMPRESSIBLE, StreamTextOptions(topic = TOPIC))

        val packets = awaitPackets(3)
        assertEquals(3, packets.size)
        assertEquals(DataStream.CompressionType.NONE, packets[0].header.compression)
        assertFalse(packets[0].header.hasInlineContent())
        assertEquals(COMPRESSIBLE, packets[1].streamChunk.content.toStringUtf8())
    }

    /** Narrowing the same send to capable recipients re-enables both v2 features. */
    @Test
    fun targetedSendToCapableSubsetOfMixedRoomUsesV2() = runTest {
        remotes = MIXED

        dataStreams.sendText(
            COMPRESSIBLE,
            StreamTextOptions(topic = TOPIC, destinationIdentities = destinations("alice", "bob")),
        )

        val packets = awaitPackets(1)
        assertEquals(1, packets.size)
        assertEquals(DataStream.CompressionType.DEFLATE_RAW, packets[0].header.compression)
    }

    @Test
    fun targetedSendIncludingAnIncapableRecipientDropsCompression() = runTest {
        remotes = MIXED

        dataStreams.sendText(
            COMPRESSIBLE,
            StreamTextOptions(topic = TOPIC, destinationIdentities = destinations("alice", "bob", "noCompression")),
        )

        val packets = awaitPackets(1)
        assertEquals(1, packets.size)
        assertEquals(DataStream.CompressionType.NONE, packets[0].header.compression)
        assertEquals(COMPRESSIBLE, packets[0].header.inlineContent.toStringUtf8())
    }

    /**
     * An empty room has nobody who could fail to understand v2, so the fast path applies.
     */
    @Test
    fun emptyRoomIsEligibleForV2() = runTest {
        remotes = emptyMap()

        dataStreams.sendText(COMPRESSIBLE, StreamTextOptions(topic = TOPIC))

        val packets = awaitPackets(1)
        assertEquals(1, packets.size)
        assertTrue(packets[0].header.hasInlineContent())
    }

    // endregion

    // region Options mapping

    @Test
    fun textOptionsReachTheWire() = runTest {
        remotes = PRE_V2

        dataStreams.sendText(
            "hi",
            StreamTextOptions(
                topic = TOPIC,
                attributes = mapOf("foo" to "bar"),
                streamId = "explicit-id",
                destinationIdentities = destinations("alice"),
                operationType = TextStreamInfo.OperationType.UPDATE,
                version = 7,
                replyToStreamId = "earlier",
                attachedStreamIds = listOf("att-1"),
            ),
        )

        val header = awaitPackets(3)[0].header
        assertEquals("explicit-id", header.streamId)
        assertEquals(TOPIC, header.topic)
        assertEquals("bar", header.attributesMap["foo"])
        assertEquals(listOf("alice"), sentPackets[0].destinationIdentitiesList)
        assertEquals(DataStream.OperationType.UPDATE, header.textHeader.operationType)
        assertEquals(7, header.textHeader.version)
        assertEquals("earlier", header.textHeader.replyToStreamId)
        assertEquals(listOf("att-1"), header.textHeader.attachedStreamIdsList)
    }

    @Test
    fun byteOptionsReachTheWire() = runTest {
        remotes = PRE_V2

        dataStreams.sendBytes(
            byteArrayOf(1, 2, 3),
            StreamBytesOptions(
                topic = TOPIC,
                attributes = mapOf("k" to "v"),
                streamId = "bytes-id",
                mimeType = "image/png",
                name = "pic.png",
                totalSize = 3,
            ),
        )

        val header = awaitPackets(3)[0].header
        assertEquals("bytes-id", header.streamId)
        assertEquals("image/png", header.mimeType)
        assertEquals("pic.png", header.byteHeader.name)
        assertEquals("v", header.attributesMap["k"])
        assertEquals(3L, header.totalLength)
    }

    /** Everything the core emits is reliable; data streams must not race down the lossy channel. */
    @Test
    fun packetsAreSentReliably() = runTest {
        remotes = PRE_V2

        dataStreams.sendText("hi", StreamTextOptions(topic = TOPIC))

        awaitPackets(3).forEach {
            assertEquals(DataPacket.Kind.RELIABLE, it.kind)
        }
    }

    @Test
    fun returnedInfoDescribesTheStream() = runTest {
        remotes = PRE_V2

        val info = dataStreams.sendText(
            "hello",
            StreamTextOptions(topic = TOPIC, streamId = "id-1", attributes = mapOf("a" to "b")),
        )

        assertEquals("id-1", info.id)
        assertEquals(TOPIC, info.topic)
        assertEquals("b", info.attributes["a"])
        assertEquals(5L, info.totalSize)
        assertEquals(LivekitModels.Encryption.Type.NONE, info.encryptionType)
    }

    // endregion

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
