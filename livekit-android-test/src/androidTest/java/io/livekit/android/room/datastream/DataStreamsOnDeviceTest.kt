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

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.livekit.android.room.ClientCapability
import io.livekit.android.room.ClientProtocolVersion
import io.livekit.uniffi.buildVersion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import livekit.LivekitModels
import livekit.LivekitModels.DataPacket
import livekit.LivekitModels.DataStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.livekit.uniffi.ByteStreamReader as FfiByteStreamReader
import io.livekit.uniffi.ClientCapability as FfiClientCapability
import io.livekit.uniffi.IncomingDataStreamManager as FfiIncomingDataStreamManager
import io.livekit.uniffi.IncomingDataStreamManagerDelegate as FfiIncomingDelegate
import io.livekit.uniffi.OutgoingDataStreamManager as FfiOutgoingDataStreamManager
import io.livekit.uniffi.OutgoingDataStreamManagerDelegate as FfiOutgoingDelegate
import io.livekit.uniffi.RemoteParticipantRegistryDelegate as FfiRegistryDelegate
import io.livekit.uniffi.StreamTextOptions as FfiStreamTextOptions
import io.livekit.uniffi.TextStreamReader as FfiTextStreamReader

/**
 * Runs the data stream core on a real Android device.
 *
 * The unit tests exercise the same code but on a host JVM against a host build of the library,
 * which leaves a few things unproven that only Android can settle:
 *
 *  - that the `.so` in the AAR loads on Android at all, through JNA, from the packaged jniLibs;
 *  - that the bindings' Android-specific resource cleaner works. It is selected at API 34 and
 *    above and uses `android.system.SystemCleaner`, which under Robolectric throws
 *    IllegalAccessError and needs a JVM flag to work around. This is the only place that path runs
 *    as written;
 *  - that the Rust core's async runtime and its threads behave on Android's runtime;
 *  - that the v2 framings are byte-identical to what the host build produces.
 *
 * Deliberately no mocking framework: androidTest has no Mockito here, so this drives the FFI
 * directly with a capturing delegate, the same seam the Swift SDK's tests use. That also means it
 * covers the layer this SDK owns -- the conversions and the error mapping -- without needing an
 * engine or a room.
 */
@RunWith(AndroidJUnit4::class)
class DataStreamsOnDeviceTest {

    private companion object {
        const val TOPIC = "topic"
        const val SENDER = "alice"
        const val COMPRESSIBLE = "hello hello compressible world"
        val TIMEOUT = 10L to TimeUnit.SECONDS
    }

    private class CapturingDelegate : FfiOutgoingDelegate {
        val packets = CopyOnWriteArrayList<DataPacket>()
        override fun onPacketsAvailable(packets: List<ByteArray>) {
            for (bytes in packets) {
                this.packets.add(DataPacket.parseFrom(bytes))
            }
        }
    }

    /** Reports whatever the test wants the core to believe about the room. */
    private class StubRegistry(
        private val protocol: Int,
        private val capabilities: List<ClientCapability>,
        private val identities: List<String> = listOf(SENDER),
    ) : FfiRegistryDelegate {
        override fun remoteClientProtocol(identity: String) = protocol
        override fun remoteCapabilities(identity: String) = capabilities.map { it.toFfi() }
        override fun remoteIdentities() = identities
    }

    private class OpenedStreams : FfiIncomingDelegate {
        val text = CopyOnWriteArrayList<FfiTextStreamReader>()
        val bytes = CopyOnWriteArrayList<FfiByteStreamReader>()
        val latch = CountDownLatch(1)

        override fun onTextStreamOpened(reader: FfiTextStreamReader, identity: String) {
            text.add(reader)
            latch.countDown()
        }

        override fun onByteStreamOpened(reader: FfiByteStreamReader, identity: String) {
            bytes.add(reader)
            latch.countDown()
        }
    }

    private fun CapturingDelegate.awaitPackets(count: Int) {
        val deadline = System.currentTimeMillis() + TIMEOUT.first * 1000
        while (packets.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        // Settle briefly so "exactly N" assertions mean something.
        Thread.sleep(200)
        assertTrue(
            "expected at least $count packet(s) on device, saw ${packets.size}",
            packets.size >= count,
        )
    }

    /**
     * The library has to load before anything else here can work, and this is the call that forces
     * both the JNA registration and the checksum check between the bindings and the `.so`.
     */
    @Test
    fun nativeLibraryLoadsOnDevice() {
        val version = buildVersion()

        assertTrue("livekit-uniffi reported an empty build version", version.isNotEmpty())
    }

    /**
     * The cleaner the bindings pick is API dependent, and the branch taken here is the one the host
     * tests cannot run. Asserted so a device running an older API is not silently taken as
     * covering it.
     */
    @Test
    fun runsOnAnApiLevelThatUsesTheAndroidCleaner() {
        assertTrue(
            "this device is API ${Build.VERSION.SDK_INT}; the Android cleaner path needs 34+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )
    }

    @Test
    fun sendsLegacyFramingToPreV2Recipients() = runBlocking {
        val delegate = CapturingDelegate()
        val manager = FfiOutgoingDataStreamManager(
            delegate,
            StubRegistry(ClientProtocolVersion.DEFAULT.value, emptyList()),
        )

        withTimeout(TIMEOUT.second.toMillis(TIMEOUT.first)) {
            manager.sendText("hello world", FfiStreamTextOptions(topic = TOPIC, attributes = emptyMap()))
        }
        delegate.awaitPackets(3)

        assertEquals(3, delegate.packets.size)
        val header = delegate.packets[0].streamHeader
        assertTrue(delegate.packets[0].hasStreamHeader())
        assertEquals(DataStream.CompressionType.NONE, header.compression)
        assertFalse(header.hasInlineContent())
        assertEquals("hello world", delegate.packets[1].streamChunk.content.toStringUtf8())
        assertTrue(delegate.packets[2].hasStreamTrailer())
        manager.destroy()
    }

    @Test
    fun sendsInlineCompressedToV2Recipients() = runBlocking {
        val delegate = CapturingDelegate()
        val manager = FfiOutgoingDataStreamManager(
            delegate,
            StubRegistry(
                ClientProtocolVersion.DATA_STREAM_V2.value,
                listOf(ClientCapability.COMPRESSION_DEFLATE_RAW),
            ),
        )

        withTimeout(TIMEOUT.second.toMillis(TIMEOUT.first)) {
            manager.sendText(COMPRESSIBLE, FfiStreamTextOptions(topic = TOPIC, attributes = emptyMap()))
        }
        delegate.awaitPackets(1)

        assertEquals("an inline send is a single packet", 1, delegate.packets.size)
        val header = delegate.packets[0].streamHeader
        assertEquals(DataStream.CompressionType.DEFLATE_RAW, header.compression)
        assertTrue(header.hasInlineContent())
        assertFalse(
            "inline content should be compressed, not raw text",
            header.inlineContent.toStringUtf8() == COMPRESSIBLE,
        )
        manager.destroy()
    }

    /** Compression is gated on the capability even when the protocol version allows inline. */
    @Test
    fun omitsCompressionForRecipientsWithoutTheCapability() = runBlocking {
        val delegate = CapturingDelegate()
        val manager = FfiOutgoingDataStreamManager(
            delegate,
            StubRegistry(ClientProtocolVersion.DATA_STREAM_V2.value, emptyList()),
        )

        withTimeout(TIMEOUT.second.toMillis(TIMEOUT.first)) {
            manager.sendText(COMPRESSIBLE, FfiStreamTextOptions(topic = TOPIC, attributes = emptyMap()))
        }
        delegate.awaitPackets(1)

        assertEquals(1, delegate.packets.size)
        val header = delegate.packets[0].streamHeader
        assertEquals(DataStream.CompressionType.NONE, header.compression)
        assertEquals(COMPRESSIBLE, header.inlineContent.toStringUtf8())
        manager.destroy()
    }

    /**
     * Loops the core's own output back into its input on the device.
     *
     * The strongest single check available without a server: whatever framing the send path chose --
     * here inline and compressed -- the receive path reconstructs the original payload from exactly
     * those bytes, with both halves running on Android.
     */
    @Test
    fun compressedInlineStreamRoundTripsThroughTheCore() = runBlocking {
        val payload = buildString { repeat(200) { append("round trip me ") } }

        val sent = CapturingDelegate()
        val outgoing = FfiOutgoingDataStreamManager(
            sent,
            StubRegistry(
                ClientProtocolVersion.DATA_STREAM_V2.value,
                listOf(ClientCapability.COMPRESSION_DEFLATE_RAW),
            ),
        )
        withTimeout(TIMEOUT.second.toMillis(TIMEOUT.first)) {
            outgoing.sendText(payload, FfiStreamTextOptions(topic = TOPIC, attributes = emptyMap()))
        }
        sent.awaitPackets(1)
        assertEquals(DataStream.CompressionType.DEFLATE_RAW, sent.packets[0].streamHeader.compression)

        val opened = OpenedStreams()
        val incoming = FfiIncomingDataStreamManager(opened, null)
        for (packet in sent.packets) {
            // Stamp a sender, which the wire carries but a capturing delegate never sees.
            incoming.handlePacketReceived(
                packet.toBuilder().setParticipantIdentity(SENDER).build().toByteArray(),
            )
        }

        assertTrue(
            "the core never surfaced the stream it had just produced",
            opened.latch.await(TIMEOUT.first, TIMEOUT.second),
        )
        val received = withTimeout(TIMEOUT.second.toMillis(TIMEOUT.first)) {
            opened.text.first().readAll()
        }

        assertEquals(payload, received)
        assertEquals(TOPIC, opened.text.first().info().topic)
        outgoing.destroy()
        incoming.destroy()
    }

    /** Multi-byte text has to survive the encode/decode the SDK's reader glue does. */
    @Test
    fun multiByteTextRoundTripsOnDevice() = runBlocking {
        val text = "héllo → 世界 🎉 café"

        val sent = CapturingDelegate()
        val outgoing = FfiOutgoingDataStreamManager(
            sent,
            StubRegistry(ClientProtocolVersion.DEFAULT.value, emptyList()),
        )
        withTimeout(TIMEOUT.second.toMillis(TIMEOUT.first)) {
            outgoing.sendText(text, FfiStreamTextOptions(topic = TOPIC, attributes = emptyMap()))
        }
        sent.awaitPackets(3)

        val opened = OpenedStreams()
        val incoming = FfiIncomingDataStreamManager(opened, null)
        for (packet in sent.packets) {
            incoming.handlePacketReceived(
                packet.toBuilder().setParticipantIdentity(SENDER).build().toByteArray(),
            )
        }
        assertTrue(opened.latch.await(TIMEOUT.first, TIMEOUT.second))

        val received = withTimeout(TIMEOUT.second.toMillis(TIMEOUT.first)) {
            opened.text.first().readAll()
        }
        // Round-tripped through the byte channel the public reader is built on, as the SDK does.
        val throughByteChannel = received.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)

        assertEquals(text, throughByteChannel)
        outgoing.destroy()
        incoming.destroy()
    }

    // region This SDK's own translation layer, exercised in an Android runtime

    @Test
    fun streamInfoConversionWorksOnDevice() = runBlocking {
        val sent = CapturingDelegate()
        val outgoing = FfiOutgoingDataStreamManager(
            sent,
            StubRegistry(ClientProtocolVersion.DEFAULT.value, emptyList()),
        )

        val info = withTimeout(TIMEOUT.second.toMillis(TIMEOUT.first)) {
            outgoing.sendText(
                "hello",
                FfiStreamTextOptions(topic = TOPIC, attributes = mapOf("a" to "b"), id = "id-1"),
            )
        }.toSdk(LivekitModels.Encryption.Type.GCM)

        assertEquals("id-1", info.id)
        assertEquals(TOPIC, info.topic)
        assertEquals("b", info.attributes["a"])
        assertEquals(5L, info.totalSize)
        assertEquals(LivekitModels.Encryption.Type.GCM, info.encryptionType)
        outgoing.destroy()
    }

    @Test
    fun capabilityAndOperationTypeMappingWorksOnDevice() {
        assertEquals(
            FfiClientCapability.COMPRESSION_DEFLATE_RAW,
            ClientCapability.COMPRESSION_DEFLATE_RAW.toFfi(),
        )
        for (value in TextStreamInfo.OperationType.entries) {
            assertEquals(value, value.toFfi().toSdk())
        }
    }

    @Test
    fun errorMappingWorksOnDevice() {
        val header = io.livekit.uniffi.DataStreamException.HeaderTooLarge().toStreamException()
        assertTrue(header is StreamException.HeaderTooLargeException)
        assertTrue("must stay catchable as the older type", header is StreamException.LengthExceededException)

        val terminated = io.livekit.uniffi.DataStreamException.MissedChunk().toStreamException()
        assertEquals(
            StreamException.TerminatedException.Reason.MISSED_CHUNK,
            (terminated as StreamException.TerminatedException).reason,
        )
    }

    // endregion
}
