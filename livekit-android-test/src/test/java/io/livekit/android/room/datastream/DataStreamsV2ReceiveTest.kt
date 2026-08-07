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

import com.google.protobuf.ByteString
import io.livekit.android.e2ee.E2EEManager
import io.livekit.android.memory.CloseableManager
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.datastream.incoming.ByteStreamReceiver
import io.livekit.android.room.datastream.incoming.TextStreamReceiver
import io.livekit.android.room.participant.Participant
import io.livekit.android.test.BaseTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitModels
import livekit.LivekitModels.DataPacket
import livekit.LivekitModels.DataStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.Deflater

/**
 * The v2 receive path through [DataStreams].
 *
 * The core does the reassembly and decompression, so what is actually under test here is our glue:
 * that we hand it whole packets, route the streams it opens to the right topic handler, and surface
 * their content and failures through the SDK's own reader types.
 *
 * The legacy header/chunk/trailer cases are already covered at the Room level by
 * RoomIncomingDataStreamMockE2ETest; these are the v2 framings and the wiring around them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DataStreamsV2ReceiveTest : BaseTest() {

    @Mock
    lateinit var engine: RTCEngine

    private lateinit var dataStreams: DataStreams

    private val textStreams = CopyOnWriteArrayList<Pair<TextStreamReceiver, Participant.Identity>>()
    private val byteStreams = CopyOnWriteArrayList<Pair<ByteStreamReceiver, Participant.Identity>>()

    private companion object {
        const val TOPIC = "topic"
        const val SENDER = "alice"
        const val STREAM_ID = "stream-1"
    }

    @Before
    fun setup() {
        engine.stub { on { e2EEManager } doReturn null }
        dataStreams = DataStreams(engine = engine, closeableManager = CloseableManager())
        dataStreams.registerTextStreamHandler(TOPIC) { reader, identity -> textStreams.add(reader to identity) }
        dataStreams.registerByteStreamHandler(TOPIC) { reader, identity -> byteStreams.add(reader to identity) }
    }

    @After
    fun tearDown() {
        dataStreams.close()
    }

    // region Packet building

    private fun deflateRaw(input: ByteArray): ByteArray {
        // nowrap = true gives raw DEFLATE, with no zlib header, which is the wire format.
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArray(input.size * 2 + 64)
        val written = deflater.deflate(out)
        deflater.end()
        return out.copyOf(written)
    }

    private fun header(
        text: Boolean,
        inlineContent: ByteArray? = null,
        compression: DataStream.CompressionType = DataStream.CompressionType.NONE,
        totalLength: Long? = null,
        attributes: Map<String, String> = emptyMap(),
        topic: String = TOPIC,
        streamId: String = STREAM_ID,
        sender: String = SENDER,
    ): DataPacket = DataPacket.newBuilder()
        .setParticipantIdentity(sender)
        .setStreamHeader(
            DataStream.Header.newBuilder()
                .setStreamId(streamId)
                .setTopic(topic)
                .setTimestamp(0)
                .putAllAttributes(attributes)
                .setCompression(compression)
                .apply {
                    if (inlineContent != null) setInlineContent(ByteString.copyFrom(inlineContent))
                    if (totalLength != null) setTotalLength(totalLength)
                    if (text) {
                        mimeType = "text/plain"
                        textHeader = DataStream.TextHeader.newBuilder()
                            .setOperationType(DataStream.OperationType.CREATE)
                            .build()
                    } else {
                        mimeType = "application/octet-stream"
                        byteHeader = DataStream.ByteHeader.newBuilder().setName("blob").build()
                    }
                }
                .build(),
        )
        .build()

    private fun chunk(content: ByteArray, index: Long = 0, streamId: String = STREAM_ID): DataPacket =
        DataPacket.newBuilder()
            .setParticipantIdentity(SENDER)
            .setStreamChunk(
                DataStream.Chunk.newBuilder()
                    .setStreamId(streamId)
                    .setChunkIndex(index)
                    .setContent(ByteString.copyFrom(content))
                    .build(),
            )
            .build()

    private fun trailer(reason: String = "", streamId: String = STREAM_ID): DataPacket =
        DataPacket.newBuilder()
            .setParticipantIdentity(SENDER)
            .setStreamTrailer(
                DataStream.Trailer.newBuilder().setStreamId(streamId).setReason(reason).build(),
            )
            .build()

    private fun awaitTextStream(): TextStreamReceiver {
        awaitCondition(message = "No text stream was opened") { textStreams.isNotEmpty() }
        return textStreams.first().first
    }

    private fun awaitByteStream(): ByteStreamReceiver {
        awaitCondition(message = "No byte stream was opened") { byteStreams.isNotEmpty() }
        return byteStreams.first().first
    }

    // endregion

    // region Inline (single packet) streams

    @Test
    fun inlineUncompressedText() = runTest {
        val text = "hello world"
        dataStreams.handleIncoming(
            header(text = true, inlineContent = text.toByteArray(), totalLength = text.length.toLong()),
        )

        // Deliberately no chunk or trailer: an inline stream is complete on its own.
        assertEquals(text, awaitTextStream().readAll().joinToString(""))
    }

    @Test
    fun inlineCompressedText() = runTest {
        val text = "hello hello compressible world"
        dataStreams.handleIncoming(
            header(
                text = true,
                inlineContent = deflateRaw(text.toByteArray()),
                compression = DataStream.CompressionType.DEFLATE_RAW,
                totalLength = text.toByteArray().size.toLong(),
            ),
        )

        assertEquals(text, awaitTextStream().readAll().joinToString(""))
    }

    @Test
    fun inlineUncompressedBytes() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        dataStreams.handleIncoming(
            header(text = false, inlineContent = payload, totalLength = 3),
        )

        val received = awaitByteStream().readAll().reduce { a, b -> a + b }
        assertEquals(payload.toList(), received.toList())
    }

    @Test
    fun inlineCompressedBytes() = runTest {
        val payload = ByteArray(500) { (it % 7).toByte() }
        dataStreams.handleIncoming(
            header(
                text = false,
                inlineContent = deflateRaw(payload),
                compression = DataStream.CompressionType.DEFLATE_RAW,
                totalLength = payload.size.toLong(),
            ),
        )

        val received = awaitByteStream().readAll().reduce { a, b -> a + b }
        assertEquals(payload.toList(), received.toList())
    }

    // endregion

    // region Chunked compressed streams

    /**
     * A compressed stream is one deflate stream spread across chunks, so the receiver has to feed
     * them through a single decompressor in order rather than decompressing each chunk.
     */
    @Test
    fun chunkedCompressedTextSplitAcrossChunks() = runTest {
        val text = buildString { repeat(2_000) { append("compress me please ") } }
        val compressed = deflateRaw(text.toByteArray())
        assertTrue("test needs the compressed form to span chunks", compressed.size > 1)
        val split = compressed.size / 2

        dataStreams.handleIncoming(
            header(
                text = true,
                compression = DataStream.CompressionType.DEFLATE_RAW,
                totalLength = text.toByteArray().size.toLong(),
            ),
        )
        dataStreams.handleIncoming(chunk(compressed.copyOfRange(0, split), index = 0))
        dataStreams.handleIncoming(chunk(compressed.copyOfRange(split, compressed.size), index = 1))
        dataStreams.handleIncoming(trailer())

        assertEquals(text, awaitTextStream().readAll().joinToString(""))
    }

    /**
     * Text crosses the FFI as decoded strings but the SDK's reader is built on a byte channel, so
     * the glue re-encodes. Multi-byte characters would break if a chunk were ever split mid
     * character.
     */
    @Test
    fun multiByteTextRoundTrips() = runTest {
        val text = "héllo → 世界 🎉 café"
        dataStreams.handleIncoming(
            header(
                text = true,
                inlineContent = text.toByteArray(),
                totalLength = text.toByteArray().size.toLong(),
            ),
        )

        assertEquals(text, awaitTextStream().readAll().joinToString(""))
    }

    // endregion

    // region Routing and lifecycle

    @Test
    fun streamOnUnhandledTopicIsIgnored() = runTest {
        dataStreams.handleIncoming(
            header(text = true, inlineContent = "hi".toByteArray(), topic = "other-topic"),
        )
        // Then a stream we do handle, as a barrier proving the first was processed and dropped.
        dataStreams.handleIncoming(
            header(text = true, inlineContent = "hi".toByteArray(), streamId = "stream-2"),
        )

        awaitCondition { textStreams.isNotEmpty() }
        awaitStable { textStreams.size }
        assertEquals(1, textStreams.size)
        assertEquals("stream-2", textStreams.first().first.info.id)
    }

    @Test
    fun senderIdentityIsSurfacedToTheHandler() = runTest {
        dataStreams.handleIncoming(header(text = true, inlineContent = "hi".toByteArray()))

        awaitCondition { textStreams.isNotEmpty() }
        assertEquals(Participant.Identity(SENDER), textStreams.first().second)
    }

    @Test
    fun headerAttributesReachStreamInfo() = runTest {
        dataStreams.handleIncoming(
            header(
                text = true,
                inlineContent = "hi".toByteArray(),
                attributes = mapOf("foo" to "bar"),
            ),
        )

        val info = awaitTextStream().info
        assertEquals(STREAM_ID, info.id)
        assertEquals(TOPIC, info.topic)
        assertEquals("bar", info.attributes["foo"])
    }

    @Test
    fun unregisteringAHandlerStopsDelivery() = runTest {
        dataStreams.unregisterTextStreamHandler(TOPIC)

        dataStreams.handleIncoming(header(text = true, inlineContent = "hi".toByteArray()))
        // Byte stream on the same topic still has a handler, and acts as the barrier.
        dataStreams.handleIncoming(
            header(text = false, inlineContent = byteArrayOf(1), streamId = "stream-b"),
        )

        awaitCondition { byteStreams.isNotEmpty() }
        awaitStable { textStreams.size }
        assertTrue(textStreams.isEmpty())
    }

    /** A sender that disconnects mid-stream must fail its readers rather than leave them waiting. */
    @Test
    fun abortStreamsFromFailsThatSendersOpenStreams() = runTest {
        dataStreams.handleIncoming(header(text = true, totalLength = 100))
        dataStreams.handleIncoming(chunk("partial".toByteArray()))
        val reader = awaitTextStream()

        dataStreams.abortStreamsFrom(Participant.Identity(SENDER))

        val error = runCatching { reader.readAll() }.exceptionOrNull()
        assertTrue("expected a StreamException, got $error", error is StreamException)
    }

    @Test
    fun abortAllStreamsFailsOpenStreams() = runTest {
        dataStreams.handleIncoming(header(text = true, totalLength = 100))
        dataStreams.handleIncoming(chunk("partial".toByteArray()))
        val reader = awaitTextStream()

        dataStreams.abortAllStreams()

        val error = runCatching { reader.readAll() }.exceptionOrNull()
        assertTrue("expected a StreamException, got $error", error is StreamException)
    }

    /** Handler registrations outlive an abort, so streams after a reconnect are still delivered. */
    @Test
    fun handlersSurviveAbortAllStreams() = runTest {
        dataStreams.abortAllStreams()

        dataStreams.handleIncoming(header(text = true, inlineContent = "after".toByteArray()))

        assertEquals("after", awaitTextStream().readAll().joinToString(""))
    }

    // endregion

    // region Failures

    @Test
    fun trailerWithReasonSurfacesAbnormalEnd() = runTest {
        dataStreams.handleIncoming(header(text = true))
        val reader = awaitTextStream()
        dataStreams.handleIncoming(chunk("partial".toByteArray()))
        dataStreams.handleIncoming(trailer(reason = "sender gave up"))

        val error = runCatching { reader.readAll() }.exceptionOrNull()
        assertTrue(
            "expected AbnormalEndException, got $error",
            error is StreamException.AbnormalEndException,
        )
    }

    @Test
    fun shortStreamSurfacesIncomplete() = runTest {
        dataStreams.handleIncoming(header(text = true, totalLength = 100))
        val reader = awaitTextStream()
        dataStreams.handleIncoming(chunk("tiny".toByteArray()))
        dataStreams.handleIncoming(trailer())

        val error = runCatching { reader.readAll() }.exceptionOrNull()
        assertTrue("expected IncompleteException, got $error", error is StreamException.IncompleteException)
    }

    @Test
    fun overlongStreamSurfacesLengthExceeded() = runTest {
        dataStreams.handleIncoming(header(text = true, totalLength = 3))
        val reader = awaitTextStream()
        dataStreams.handleIncoming(chunk("far too much content".toByteArray()))
        dataStreams.handleIncoming(trailer())

        val error = runCatching { reader.readAll() }.exceptionOrNull()
        assertTrue(
            "expected LengthExceededException, got $error",
            error is StreamException.LengthExceededException,
        )
    }

    /**
     * The payload cap is read when the first packet arrives, not at construction, so that a value
     * passed to connect() is picked up.
     */
    @Test
    fun maxPayloadSizeIsEnforced() = runTest {
        dataStreams.maxPayloadSize = { 16 }

        dataStreams.handleIncoming(header(text = true))
        val reader = awaitTextStream()
        dataStreams.handleIncoming(chunk(ByteArray(1_000) { 'a'.code.toByte() }))

        val error = runCatching { reader.readAll() }.exceptionOrNull()
        assertTrue("expected a StreamException, got $error", error is StreamException)
    }

    // endregion

    // region Encryption
    //
    // Transport encryption is applied and undone in RTCEngine, on the whole packet, either side of
    // the FFI: the core only ever sees plaintext and reports NONE on every stream, so holding a
    // stream to how it arrived is the SDK's job.

    @Test
    fun streamInfoReportsTheEncryptionTheStreamArrivedUnder() = runTest {
        dataStreams.handleIncoming(
            header(text = true, inlineContent = "hi".toByteArray()),
            LivekitModels.Encryption.Type.GCM,
        )

        assertEquals(LivekitModels.Encryption.Type.GCM, awaitTextStream().info.encryptionType)
    }

    /**
     * Reporting the room's configuration rather than what arrived told an app checking
     * [StreamInfo.encryptionType] what it wanted to hear: a plaintext stream came through labelled
     * GCM purely because this end had encryption turned on.
     */
    @Test
    fun plaintextStreamInAnEncryptedRoomIsReportedAsPlaintext() = runTest {
        val mockE2EEManager = mock<E2EEManager>()
        mockE2EEManager.stub { on { isDataChannelEncryptionEnabled() } doReturn true }
        engine.stub { on { e2EEManager } doReturn mockE2EEManager }

        dataStreams.handleIncoming(
            header(text = true, inlineContent = "hi".toByteArray()),
            LivekitModels.Encryption.Type.NONE,
        )

        assertEquals(LivekitModels.Encryption.Type.NONE, awaitTextStream().info.encryptionType)
    }

    /** A stream cannot open encrypted and then carry on in plaintext. */
    @Test
    fun aChunkThatChangesEncryptionFailsTheStream() = runTest {
        dataStreams.handleIncoming(
            header(text = true, totalLength = 100),
            LivekitModels.Encryption.Type.GCM,
        )
        val reader = awaitTextStream()

        dataStreams.handleIncoming(chunk("plaintext".toByteArray()), LivekitModels.Encryption.Type.NONE)

        val error = runCatching { reader.readAll() }.exceptionOrNull()
        assertTrue(
            "expected EncryptionTypeMismatch, got $error",
            error is StreamException.EncryptionTypeMismatch,
        )
    }

    /**
     * The core opens a stream on its own loop, so a contradicting chunk can be handed in before the
     * reader exists. The reader still has to raise rather than wait for chunks that will never come.
     */
    @Test
    fun aStreamFailedBeforeItsReaderExistsStillRaises() = runTest {
        dataStreams.handleIncoming(
            header(text = true, totalLength = 100),
            LivekitModels.Encryption.Type.GCM,
        )
        // Deliberately no await in between.
        dataStreams.handleIncoming(chunk("plaintext".toByteArray()), LivekitModels.Encryption.Type.NONE)

        val error = runCatching { awaitTextStream().readAll() }.exceptionOrNull()
        assertTrue(
            "expected EncryptionTypeMismatch, got $error",
            error is StreamException.EncryptionTypeMismatch,
        )
    }

    /** A trailer is checked the same way, so a stream cannot be ended by an unencrypted peer. */
    @Test
    fun aTrailerThatChangesEncryptionFailsTheStream() = runTest {
        dataStreams.handleIncoming(
            header(text = true, totalLength = 100),
            LivekitModels.Encryption.Type.GCM,
        )
        val reader = awaitTextStream()

        dataStreams.handleIncoming(trailer(), LivekitModels.Encryption.Type.NONE)

        val error = runCatching { reader.readAll() }.exceptionOrNull()
        assertTrue(
            "expected EncryptionTypeMismatch, got $error",
            error is StreamException.EncryptionTypeMismatch,
        )
    }

    @Test
    fun consistentlyEncryptedStreamsAreDeliveredNormally() = runTest {
        val gcm = LivekitModels.Encryption.Type.GCM
        dataStreams.handleIncoming(header(text = true, totalLength = 5), gcm)
        dataStreams.handleIncoming(chunk("hello".toByteArray()), gcm)
        dataStreams.handleIncoming(trailer(), gcm)

        assertEquals("hello", awaitTextStream().readAll().joinToString(""))
    }

    // endregion

    // region Failures

    @Test
    fun nonDataStreamPacketsAreIgnored() = runTest {
        // A user packet has no stream fields at all; the core should drop it without complaint.
        dataStreams.handleIncoming(
            DataPacket.newBuilder()
                .setParticipantIdentity(SENDER)
                .setUser(livekit.LivekitModels.UserPacket.newBuilder().setPayload(ByteString.copyFromUtf8("x")))
                .build(),
        )
        dataStreams.handleIncoming(header(text = true, inlineContent = "ok".toByteArray()))

        assertEquals("ok", awaitTextStream().readAll().joinToString(""))
    }

    // endregion
}
