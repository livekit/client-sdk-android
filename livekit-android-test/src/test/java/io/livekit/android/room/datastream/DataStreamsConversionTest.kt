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

import io.livekit.android.room.ClientCapability
import io.livekit.android.room.participant.Participant
import livekit.LivekitModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.livekit.uniffi.ByteStreamInfo as FfiByteStreamInfo
import io.livekit.uniffi.ClientCapability as FfiClientCapability
import io.livekit.uniffi.DataStreamException as FfiDataStreamException
import io.livekit.uniffi.EncryptionType as FfiEncryptionType
import io.livekit.uniffi.OperationType as FfiOperationType
import io.livekit.uniffi.TextStreamInfo as FfiTextStreamInfo

/**
 * The pure translation layer between the core's types and this SDK's.
 *
 * Worth testing directly because most of it is only reachable through a live stream otherwise, and
 * because the error mapping is lossy by design: the public [StreamException] hierarchy predates the
 * core and several new failure modes fold onto one existing case. A wrong fold turns a diagnosable
 * failure into a misleading one, and nothing else would catch it.
 */
class DataStreamsConversionTest {

    private val encryption = LivekitModels.Encryption.Type.GCM

    // region Info records

    @Test
    fun textStreamInfoConversion() {
        val info = FfiTextStreamInfo(
            id = "id",
            topic = "topic",
            timestampMs = 1_700_000_000_000,
            totalLength = 42UL,
            attributes = mapOf("a" to "b"),
            mimeType = "text/plain",
            operationType = FfiOperationType.UPDATE,
            version = 3,
            replyToStreamId = "reply-to",
            attachedStreamIds = listOf("att"),
            generated = true,
            encryptionType = FfiEncryptionType.NONE,
        ).toSdk(encryption)

        assertEquals("id", info.id)
        assertEquals("topic", info.topic)
        assertEquals(1_700_000_000_000, info.timestampMs)
        assertEquals(42L, info.totalSize)
        assertEquals(mapOf("a" to "b"), info.attributes)
        assertEquals(TextStreamInfo.OperationType.UPDATE, info.operationType)
        assertEquals(3, info.version)
        assertEquals("reply-to", info.replyToStreamId)
        assertEquals(listOf("att"), info.attachedStreamIds)
        assertTrue(info.generated)
        // The core reports NONE on every stream; the room's real value is stamped on instead.
        assertEquals(encryption, info.encryptionType)
    }

    @Test
    fun byteStreamInfoConversion() {
        val info = FfiByteStreamInfo(
            id = "id",
            topic = "topic",
            timestampMs = 5,
            totalLength = null,
            attributes = emptyMap(),
            mimeType = "image/png",
            name = "pic.png",
            encryptionType = FfiEncryptionType.NONE,
        ).toSdk(encryption)

        assertEquals("pic.png", info.name)
        assertEquals("image/png", info.mimeType)
        assertNull("an unknown-length stream must not report a size", info.totalSize)
        assertEquals(encryption, info.encryptionType)
    }

    // endregion

    // region Enums

    @Test
    fun operationTypeRoundTrips() {
        for (value in TextStreamInfo.OperationType.entries) {
            assertEquals(value, value.toFfi().toSdk())
        }
    }

    @Test
    fun clientCapabilityMapsToFfi() {
        assertEquals(FfiClientCapability.PACKET_TRAILER, ClientCapability.PACKET_TRAILER.toFfi())
        assertEquals(
            FfiClientCapability.COMPRESSION_DEFLATE_RAW,
            ClientCapability.COMPRESSION_DEFLATE_RAW.toFfi(),
        )
    }

    // endregion

    // region Options

    @Test
    fun textOptionsConversion() {
        val ffi = StreamTextOptions(
            topic = "topic",
            attributes = mapOf("k" to "v"),
            streamId = "sid",
            destinationIdentities = listOf(Participant.Identity("alice"), Participant.Identity("bob")),
            operationType = TextStreamInfo.OperationType.REACTION,
            version = 2,
            attachedStreamIds = listOf("a"),
            replyToStreamId = "r",
            compress = false,
        ).toFfi()

        assertEquals("topic", ffi.topic)
        assertEquals(mapOf("k" to "v"), ffi.attributes)
        assertEquals("sid", ffi.id)
        assertEquals(listOf("alice", "bob"), ffi.destinationIdentities)
        assertEquals(FfiOperationType.REACTION, ffi.operationType)
        assertEquals(2, ffi.version)
        assertEquals(listOf("a"), ffi.attachedStreamIds)
        assertEquals("r", ffi.replyToStreamId)
        assertEquals(false, ffi.compress)
    }

    @Test
    fun byteOptionsConversion() {
        val ffi = StreamBytesOptions(
            topic = "topic",
            streamId = "sid",
            destinationIdentities = listOf(Participant.Identity("alice")),
            mimeType = "application/pdf",
            name = "doc.pdf",
            totalSize = 99,
        ).toFfi()

        assertEquals("application/pdf", ffi.mimeType)
        assertEquals("doc.pdf", ffi.name)
        assertEquals(99UL, ffi.totalLength)
        assertEquals(listOf("alice"), ffi.destinationIdentities)
        assertEquals(true, ffi.compress)
    }

    /**
     * An incremental text stream is opened as unknown-length, so a declared total has nowhere to go.
     * Asserted so the drop stays deliberate rather than becoming a silent surprise.
     */
    @Test
    fun textOptionsTotalSizeIsNotCarriedOver() {
        val options = StreamTextOptions(topic = "topic", totalSize = 1234)

        // StreamTextOptions has no total length on the FFI side at all; nothing to assert but that
        // conversion succeeds and the caller's value is not smuggled elsewhere.
        val ffi = options.toFfi()
        assertEquals("topic", ffi.topic)
        assertEquals(1234L, options.totalSize)
    }

    // endregion

    // region Error mapping

    @Test
    fun abnormalEndCarriesItsMessage() {
        val mapped = FfiDataStreamException.AbnormalEnd("sender gave up").toStreamException()

        assertTrue(mapped is StreamException.AbnormalEndException)
        assertTrue(
            "the core's reason should survive, was: ${mapped.message}",
            mapped.message?.contains("sender gave up") == true,
        )
    }

    @Test
    fun ioErrorsReadAsAbnormalEnd() {
        assertTrue(
            FfiDataStreamException.Io("disk went away").toStreamException()
                is StreamException.AbnormalEndException,
        )
    }

    @Test
    fun decodeFailures() {
        assertTrue(FfiDataStreamException.Utf8("bad").toStreamException() is StreamException.DecodeFailedException)
        assertTrue(FfiDataStreamException.Decompression().toStreamException() is StreamException.DecodeFailedException)
    }

    /**
     * Three distinct size failures fold onto one public case. Left as-is rather than widening
     * public API, but pinned here so the fold is a decision rather than an accident.
     */
    @Test
    fun sizeFailuresFoldOntoLengthExceeded() {
        assertTrue(FfiDataStreamException.LengthExceeded().toStreamException() is StreamException.LengthExceededException)
        assertTrue(FfiDataStreamException.HeaderTooLarge().toStreamException() is StreamException.LengthExceededException)
        assertTrue(FfiDataStreamException.PayloadTooLarge().toStreamException() is StreamException.LengthExceededException)
    }

    @Test
    fun incompleteAndEncryptionMismatch() {
        assertTrue(FfiDataStreamException.Incomplete().toStreamException() is StreamException.IncompleteException)
        assertTrue(
            FfiDataStreamException.EncryptionTypeMismatch().toStreamException()
                is StreamException.EncryptionTypeMismatch,
        )
    }

    @Test
    fun remainingFailuresReadAsTerminated() {
        val terminated = listOf(
            FfiDataStreamException.AlreadyClosed(),
            FfiDataStreamException.InvalidHeader(),
            FfiDataStreamException.MissedChunk(),
            FfiDataStreamException.SendFailed(),
            FfiDataStreamException.Internal(),
            FfiDataStreamException.InvalidFileName(),
        )

        for (error in terminated) {
            assertTrue(
                "${error::class.simpleName} should map to TerminatedException",
                error.toStreamException() is StreamException.TerminatedException,
            )
        }
    }

    // endregion
}
