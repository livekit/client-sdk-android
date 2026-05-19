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

package io.livekit.android.room.rpc

import com.google.protobuf.ByteString
import io.livekit.android.room.ClientProtocolVersion
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.participant.Participant
import io.livekit.android.rpc.RpcError
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.TestData.REMOTE_PARTICIPANT
import io.livekit.android.test.util.toDataChannelBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import livekit.LivekitModels
import livekit.LivekitModels.DataPacket
import livekit.LivekitModels.DataStream
import livekit.LivekitRtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RpcV2MockE2ETest : MockE2ETest() {

    private lateinit var pubDataChannel: MockDataChannel
    private lateinit var subDataChannel: MockDataChannel

    override suspend fun connect(joinResponse: LivekitRtc.SignalResponse) {
        super.connect(joinResponse)

        val pubPeerConnection = component.rtcEngine().getPublisherPeerConnection() as MockPeerConnection
        pubDataChannel = pubPeerConnection.dataChannels[RTCEngine.RELIABLE_DATA_CHANNEL_LABEL] as MockDataChannel

        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)
    }

    /** Add the test's remote participant as a v2 (data-stream-capable) client. */
    private fun simulateRemoteJoinAsV2() {
        val participantUpdate = with(LivekitRtc.SignalResponse.newBuilder()) {
            update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
                val v2Remote = REMOTE_PARTICIPANT.toBuilder()
                    .setClientProtocol(ClientProtocolVersion.DATA_STREAM_RPC.value)
                    .build()
                addParticipants(v2Remote)
                build()
            }
            build()
        }
        simulateMessageFromServer(participantUpdate)
    }

    /** Add the test's remote participant as a v1 (legacy) client. */
    private fun simulateRemoteJoinAsV1() {
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
    }

    private fun parsePacket(buffer: livekit.org.webrtc.DataChannel.Buffer): DataPacket =
        DataPacket.parseFrom(ByteString.copyFrom(buffer.data.duplicate()))

    /**
     * Find the v2 RPC request stream in the buffers sent by the local participant.
     * Returns the request attributes and the assembled UTF-8 payload, or null if no such
     * stream is present.
     */
    private fun collectOutgoingV2Stream(topic: String): Pair<Map<String, String>, String>? {
        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        val header = packets.firstOrNull { it.hasStreamHeader() && it.streamHeader.topic == topic }
            ?: return null
        val streamId = header.streamHeader.streamId
        val chunks = packets
            .filter { it.hasStreamChunk() && it.streamChunk.streamId == streamId }
            .sortedBy { it.streamChunk.chunkIndex }
        val payload = buildString {
            for (chunk in chunks) {
                append(chunk.streamChunk.content.toString(Charsets.UTF_8))
            }
        }
        return header.streamHeader.attributesMap to payload
    }

    /**
     * Simulate an inbound v2 RPC request stream landing on the subscriber data channel.
     */
    private fun simulateIncomingRequestStream(
        requestId: String,
        method: String,
        payload: String,
        version: Int = RPC_VERSION_V2,
        streamId: String = "stream-$requestId",
        responseTimeoutMs: Long = 10_000,
        fromIdentity: String = REMOTE_PARTICIPANT.identity,
    ) {
        simulateIncomingTextStream(
            streamId = streamId,
            topic = RPC_REQUEST_DATA_STREAM_TOPIC,
            payload = payload,
            attributes = mapOf(
                RpcRequestAttrs.REQUEST_ID to requestId,
                RpcRequestAttrs.METHOD to method,
                RpcRequestAttrs.RESPONSE_TIMEOUT_MS to responseTimeoutMs.toString(),
                RpcRequestAttrs.VERSION to version.toString(),
            ),
            fromIdentity = fromIdentity,
        )
    }

    /**
     * Simulate an inbound v2 RPC response stream landing on the subscriber data channel.
     */
    private fun simulateIncomingResponseStream(
        requestId: String,
        payload: String,
        streamId: String = "stream-resp-$requestId",
        fromIdentity: String = REMOTE_PARTICIPANT.identity,
    ) {
        simulateIncomingTextStream(
            streamId = streamId,
            topic = RPC_RESPONSE_DATA_STREAM_TOPIC,
            payload = payload,
            attributes = mapOf(RpcRequestAttrs.REQUEST_ID to requestId),
            fromIdentity = fromIdentity,
        )
    }

    private fun simulateIncomingTextStream(
        streamId: String,
        topic: String,
        payload: String,
        attributes: Map<String, String>,
        fromIdentity: String,
    ) {
        val headerPacket = with(DataPacket.newBuilder()) {
            participantIdentity = fromIdentity
            streamHeader = with(DataStream.Header.newBuilder()) {
                this.streamId = streamId
                this.topic = topic
                this.timestamp = 0L
                putAllAttributes(attributes)
                textHeader = with(DataStream.TextHeader.newBuilder()) {
                    operationType = DataStream.OperationType.CREATE
                    generated = false
                    build()
                }
                build()
            }
            build()
        }
        val chunkPacket = with(DataPacket.newBuilder()) {
            participantIdentity = fromIdentity
            streamChunk = with(DataStream.Chunk.newBuilder()) {
                this.streamId = streamId
                this.chunkIndex = 0L
                this.content = ByteString.copyFromUtf8(payload)
                build()
            }
            build()
        }
        val trailerPacket = with(DataPacket.newBuilder()) {
            participantIdentity = fromIdentity
            streamTrailer = with(DataStream.Trailer.newBuilder()) {
                this.streamId = streamId
                build()
            }
            build()
        }
        subDataChannel.simulateBufferReceived(headerPacket.toDataChannelBuffer())
        subDataChannel.simulateBufferReceived(chunkPacket.toDataChannelBuffer())
        subDataChannel.simulateBufferReceived(trailerPacket.toDataChannelBuffer())
    }

    private fun createAck(requestId: String) = with(DataPacket.newBuilder()) {
        participantIdentity = REMOTE_PARTICIPANT.identity
        rpcAck = with(LivekitModels.RpcAck.newBuilder()) {
            this.requestId = requestId
            build()
        }
        build()
    }.toDataChannelBuffer()

    private fun createV1Response(requestId: String, payload: String? = null, error: RpcError? = null) =
        with(DataPacket.newBuilder()) {
            participantIdentity = REMOTE_PARTICIPANT.identity
            rpcResponse = with(LivekitModels.RpcResponse.newBuilder()) {
                this.requestId = requestId
                if (error != null) {
                    this.error = error.toProto()
                } else if (payload != null) this.payload = payload
                build()
            }
            build()
        }.toDataChannelBuffer()

    // ---------------------------- v2 → v2 ---------------------------------------------------

    @Test
    fun caller_short_payload() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val rpcJob = async {
            room.localParticipant.performRpc(
                destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                method = "hello",
                payload = "hi",
            )
        }

        coroutineRule.dispatcher.scheduler.runCurrent()

        // No RpcRequest packet should be produced.
        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        assertFalse(packets.any { it.hasRpcRequest() })

        // A v2 request stream should be present with the right attributes and payload.
        val outgoing = collectOutgoingV2Stream(RPC_REQUEST_DATA_STREAM_TOPIC)
        assertNotNull(outgoing)
        val (attrs, payload) = outgoing!!
        assertEquals("hello", attrs[RpcRequestAttrs.METHOD])
        assertEquals("2", attrs[RpcRequestAttrs.VERSION])
        assertTrue(!attrs[RpcRequestAttrs.REQUEST_ID].isNullOrEmpty())
        assertEquals("hi", payload)

        val requestId = attrs[RpcRequestAttrs.REQUEST_ID]!!
        subDataChannel.simulateBufferReceived(createAck(requestId))
        simulateIncomingResponseStream(requestId, "bye")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals("bye", rpcJob.await())
    }

    @Test
    fun caller_large_payload_20k_no_error() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val largePayload = "X".repeat(20_000)
        val rpcJob = async {
            room.localParticipant.performRpc(
                destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                method = "echo",
                payload = largePayload,
            )
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        val outgoing = collectOutgoingV2Stream(RPC_REQUEST_DATA_STREAM_TOPIC)
        assertNotNull("expected a v2 request stream for a 20k payload", outgoing)
        val (attrs, payload) = outgoing!!
        assertEquals(largePayload, payload)
        val requestId = attrs[RpcRequestAttrs.REQUEST_ID]!!

        subDataChannel.simulateBufferReceived(createAck(requestId))
        simulateIncomingResponseStream(requestId, largePayload)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(largePayload, rpcJob.await())
    }

    @Test
    fun handler_short_payload() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        room.localParticipant.registerRpcMethod("hello") { "pong" }
        simulateIncomingRequestStream("req-1", "hello", "ping")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }

        // Ack travels as a v1 packet.
        val ackPacket = packets.firstOrNull { it.hasRpcAck() }
        assertNotNull(ackPacket)
        assertEquals("req-1", ackPacket!!.rpcAck.requestId)

        // No v1 RpcResponse packet — success goes via stream.
        assertFalse(packets.any { it.hasRpcResponse() })

        // Response stream is published.
        val outgoing = collectOutgoingV2Stream(RPC_RESPONSE_DATA_STREAM_TOPIC)
        assertNotNull(outgoing)
        val (attrs, payload) = outgoing!!
        assertEquals("req-1", attrs[RpcRequestAttrs.REQUEST_ID])
        assertEquals("pong", payload)
    }

    @Test
    fun handler_large_payload_20k_no_error() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val largeResponse = "X".repeat(20_000)
        room.localParticipant.registerRpcMethod("echo") { largeResponse }
        simulateIncomingRequestStream("req-large", "echo", "ping")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val outgoing = collectOutgoingV2Stream(RPC_RESPONSE_DATA_STREAM_TOPIC)
        assertNotNull("expected a v2 response stream for a 20k response", outgoing)
        val (_, payload) = outgoing!!
        assertEquals(largeResponse, payload)

        // No RESPONSE_PAYLOAD_TOO_LARGE — the v1 size check shouldn't fire for a v2 caller.
        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        val errorResponses = packets.filter { it.hasRpcResponse() && it.rpcResponse.hasError() }
        assertTrue(errorResponses.isEmpty())
    }

    @Test
    fun handler_unregistered_method_sends_ack_then_packet_error() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        simulateIncomingRequestStream("req-x", "unknown-method", "")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        // Ack first
        assertTrue(packets.any { it.hasRpcAck() && it.rpcAck.requestId == "req-x" })
        // Then a v1 error RpcResponse packet — no response stream.
        val errorResponse = packets.firstOrNull {
            it.hasRpcResponse() && it.rpcResponse.requestId == "req-x" && it.rpcResponse.hasError()
        }
        assertNotNull(errorResponse)
        assertEquals(
            RpcError.BuiltinRpcError.UNSUPPORTED_METHOD.create(),
            RpcError.fromProto(errorResponse!!.rpcResponse.error),
        )
        assertNull(collectOutgoingV2Stream(RPC_RESPONSE_DATA_STREAM_TOPIC))
    }

    @Test
    fun handler_uncaught_exception_application_error_packet() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        room.localParticipant.registerRpcMethod("boom") {
            throw RuntimeException("oops")
        }
        simulateIncomingRequestStream("req-app-err", "boom", "")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        val errorResponse = packets.firstOrNull {
            it.hasRpcResponse() && it.rpcResponse.requestId == "req-app-err" && it.rpcResponse.hasError()
        }
        assertNotNull(errorResponse)
        assertEquals(
            RpcError.BuiltinRpcError.APPLICATION_ERROR.create(),
            RpcError.fromProto(errorResponse!!.rpcResponse.error),
        )
    }

    @Test
    fun handler_rpcerror_passthrough_packet() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val custom = RpcError(101, "custom error")
        room.localParticipant.registerRpcMethod("err") { throw custom }
        simulateIncomingRequestStream("req-custom", "err", "")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        val errorResponse = packets.firstOrNull {
            it.hasRpcResponse() && it.rpcResponse.requestId == "req-custom" && it.rpcResponse.hasError()
        }
        assertNotNull(errorResponse)
        assertEquals(custom, RpcError.fromProto(errorResponse!!.rpcResponse.error))
    }

    @Test
    fun caller_response_timeout() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val rpcJob = async {
            var thrown: Throwable? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "hello",
                    payload = "hi",
                )
            } catch (e: Throwable) {
                thrown = e
            }
            thrown
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        // Ack arrives so we get past the connection-timeout window.
        val outgoing = collectOutgoingV2Stream(RPC_REQUEST_DATA_STREAM_TOPIC)!!
        val requestId = outgoing.first[RpcRequestAttrs.REQUEST_ID]!!
        subDataChannel.simulateBufferReceived(createAck(requestId))

        coroutineRule.dispatcher.scheduler.advanceTimeBy(20_000)
        assertEquals(RpcError.BuiltinRpcError.RESPONSE_TIMEOUT.create(), rpcJob.await())
    }

    @Test
    fun caller_error_response_via_packet() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val customError = RpcError(101, "boom")
        val rpcJob = async {
            var thrown: Throwable? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "x",
                    payload = "p",
                )
            } catch (e: Throwable) {
                thrown = e
            }
            thrown
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        val outgoing = collectOutgoingV2Stream(RPC_REQUEST_DATA_STREAM_TOPIC)!!
        val requestId = outgoing.first[RpcRequestAttrs.REQUEST_ID]!!
        subDataChannel.simulateBufferReceived(createAck(requestId))
        subDataChannel.simulateBufferReceived(createV1Response(requestId, error = customError))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(customError, rpcJob.await())
    }

    @Test
    fun caller_participant_disconnect() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val rpcJob = async {
            var thrown: Throwable? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "x",
                    payload = "p",
                )
            } catch (e: Throwable) {
                thrown = e
            }
            thrown
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        simulateMessageFromServer(TestData.PARTICIPANT_DISCONNECT)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            RpcError.BuiltinRpcError.RECIPIENT_DISCONNECTED.create(),
            rpcJob.await(),
        )
    }

    /**
     * Spec #11. The response arrives with no scheduler advancement between publish and reply.
     * A follow-up `performRpc` then succeeds, proving no `pendingAcks` / `pendingResponses`
     * entries were orphaned by the fast path.
     */
    @Test
    fun caller_fast_response_immediately_after_publish() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val rpcJob1 = async {
            room.localParticipant.performRpc(
                destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                method = "fast",
                payload = "p1",
            )
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        val firstHeader = pubDataChannel.sentBuffers
            .map { parsePacket(it) }
            .first { it.hasStreamHeader() && it.streamHeader.topic == RPC_REQUEST_DATA_STREAM_TOPIC }
        val requestId1 = firstHeader.streamHeader.attributesMap[RpcRequestAttrs.REQUEST_ID]!!

        // No advanceTimeBy / no scheduler tick between publish and reply.
        subDataChannel.simulateBufferReceived(createAck(requestId1))
        simulateIncomingResponseStream(requestId1, "r1", streamId = "resp-1")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals("r1", rpcJob1.await())

        // A second back-to-back call works — proves no orphaned pending entries.
        pubDataChannel.clearSentBuffers()
        val rpcJob2 = async {
            room.localParticipant.performRpc(
                destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                method = "fast",
                payload = "p2",
            )
        }
        coroutineRule.dispatcher.scheduler.runCurrent()
        val secondHeader = pubDataChannel.sentBuffers
            .map { parsePacket(it) }
            .first { it.hasStreamHeader() && it.streamHeader.topic == RPC_REQUEST_DATA_STREAM_TOPIC }
        val requestId2 = secondHeader.streamHeader.attributesMap[RpcRequestAttrs.REQUEST_ID]!!
        assertTrue("second call must get a fresh request id", requestId2 != requestId1)
        subDataChannel.simulateBufferReceived(createAck(requestId2))
        simulateIncomingResponseStream(requestId2, "r2", streamId = "resp-2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals("r2", rpcJob2.await())
    }

    /**
     * Spec #12. The ack and response are delivered synchronously from inside the publish path,
     * before `performRpc` finishes the suspending publish. Verifies that pending-response state
     * is registered *before* publish, so a response that arrives mid-publish still matches.
     */
    @Test
    fun caller_response_arrives_during_publish() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        var injected = false
        pubDataChannel.onSend = { buffer ->
            if (!injected) {
                val packet = DataPacket.parseFrom(ByteString.copyFrom(buffer.data.duplicate()))
                if (packet.hasStreamHeader() &&
                    packet.streamHeader.topic == RPC_REQUEST_DATA_STREAM_TOPIC
                ) {
                    injected = true
                    val requestId = packet.streamHeader.attributesMap[RpcRequestAttrs.REQUEST_ID]!!
                    // Inject ack + response stream synchronously, while the publishing coroutine
                    // is mid-flight (it hasn't returned from this `send` call yet).
                    subDataChannel.simulateBufferReceived(createAck(requestId))
                    simulateIncomingResponseStream(requestId, "during", streamId = "resp-during")
                }
            }
        }

        val rpcJob = async {
            room.localParticipant.performRpc(
                destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                method = "midflight",
                payload = "p",
            )
        }
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals("during", rpcJob.await())
    }

    /**
     * Spec #13. After CONNECTION_TIMEOUT fires, a delayed ack + response stream MUST NOT
     * resolve the promise a second time or otherwise crash.
     */
    @Test
    fun caller_late_ack_after_connection_timeout() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val rpcJob = async {
            var thrown: Throwable? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "late",
                    payload = "p",
                )
            } catch (e: Throwable) {
                thrown = e
            }
            thrown
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        val outgoing = collectOutgoingV2Stream(RPC_REQUEST_DATA_STREAM_TOPIC)!!
        val requestId = outgoing.first[RpcRequestAttrs.REQUEST_ID]!!

        // No ack within the 7s round-trip window → CONNECTION_TIMEOUT.
        coroutineRule.dispatcher.scheduler.advanceTimeBy(8_000)
        assertEquals(RpcError.BuiltinRpcError.CONNECTION_TIMEOUT.create(), rpcJob.await())

        // Now deliver a late ack + response. Must not throw or double-resolve.
        subDataChannel.simulateBufferReceived(createAck(requestId))
        simulateIncomingResponseStream(requestId, "too-late", streamId = "resp-late")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        // The original completion stays at CONNECTION_TIMEOUT; the rpcJob doesn't change.
        assertEquals(RpcError.BuiltinRpcError.CONNECTION_TIMEOUT.create(), rpcJob.await())
    }

    @Test
    fun caller_response_from_wrong_sender_ignored() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val rpcJob = async {
            var thrown: Throwable? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "x",
                    payload = "p",
                )
            } catch (e: Throwable) {
                thrown = e
            }
            thrown
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        val outgoing = collectOutgoingV2Stream(RPC_REQUEST_DATA_STREAM_TOPIC)!!
        val requestId = outgoing.first[RpcRequestAttrs.REQUEST_ID]!!
        subDataChannel.simulateBufferReceived(createAck(requestId))
        // Response stream from a different sender identity — must be ignored.
        simulateIncomingResponseStream(requestId, "spoofed", fromIdentity = "mallory")
        coroutineRule.dispatcher.scheduler.advanceTimeBy(20_000)

        // Should have timed out instead of resolving with "spoofed".
        assertEquals(RpcError.BuiltinRpcError.RESPONSE_TIMEOUT.create(), rpcJob.await())
    }

    @Test
    fun caller_five_concurrent_calls_no_crosstalk() = runTest {
        connect()
        simulateRemoteJoinAsV2()

        val deferred = (1..5).map { idx ->
            async {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "echo",
                    payload = "req-$idx",
                )
            }
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        // Five separate v2 request streams should have been produced.
        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        val headers = packets.filter {
            it.hasStreamHeader() && it.streamHeader.topic == RPC_REQUEST_DATA_STREAM_TOPIC
        }
        assertEquals(5, headers.size)

        // Build a map of (requestId -> expected response based on the request payload).
        val requestIdToResponse = headers.associate { header ->
            val streamId = header.streamHeader.streamId
            val requestId = header.streamHeader.attributesMap[RpcRequestAttrs.REQUEST_ID]!!
            val chunks = packets
                .filter { it.hasStreamChunk() && it.streamChunk.streamId == streamId }
                .sortedBy { it.streamChunk.chunkIndex }
            val requestPayload = buildString {
                for (chunk in chunks) {
                    append(chunk.streamChunk.content.toString(Charsets.UTF_8))
                }
            }
            requestId to "resp-${requestPayload.removePrefix("req-")}"
        }

        // Ack and respond to each.
        for ((requestId, responsePayload) in requestIdToResponse) {
            subDataChannel.simulateBufferReceived(createAck(requestId))
            simulateIncomingResponseStream(requestId, responsePayload)
        }
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        // Each call resolves with its own response, not cross-talked.
        for ((idx, d) in deferred.withIndex()) {
            assertEquals("resp-${idx + 1}", d.await())
        }
    }

    // ---------------------------- v1 ↔ v2 fallback ------------------------------------------

    @Test
    fun caller_to_v1_remote_uses_packet_not_stream() = runTest {
        connect()
        simulateRemoteJoinAsV1()

        val rpcJob = async {
            room.localParticipant.performRpc(
                destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                method = "hello",
                payload = "hi",
            )
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        val rpcRequest = packets.firstOrNull { it.hasRpcRequest() }
        assertNotNull("expected a v1 RpcRequest packet to a v1 remote", rpcRequest)
        assertNull(collectOutgoingV2Stream(RPC_REQUEST_DATA_STREAM_TOPIC))

        val requestId = rpcRequest!!.rpcRequest.id
        subDataChannel.simulateBufferReceived(createAck(requestId))
        subDataChannel.simulateBufferReceived(createV1Response(requestId, payload = "bye"))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals("bye", rpcJob.await())
    }

    @Test
    fun handler_responds_to_v1_caller_via_packet() = runTest {
        connect()
        simulateRemoteJoinAsV1()

        room.localParticipant.registerRpcMethod("hello") { "world" }
        // Feed a v1 RpcRequest packet (not a stream).
        val v1Request = with(DataPacket.newBuilder()) {
            participantIdentity = REMOTE_PARTICIPANT.identity
            rpcRequest = with(LivekitModels.RpcRequest.newBuilder()) {
                this.id = "req-v1"
                this.method = "hello"
                this.payload = "hi"
                this.responseTimeoutMs = 10_000
                this.version = RPC_VERSION_V1
                build()
            }
            build()
        }
        subDataChannel.simulateBufferReceived(v1Request.toDataChannelBuffer())
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        // Ack
        assertTrue(packets.any { it.hasRpcAck() && it.rpcAck.requestId == "req-v1" })
        // v1 success response packet (not a stream)
        val response = packets.firstOrNull {
            it.hasRpcResponse() && it.rpcResponse.requestId == "req-v1"
        }
        assertNotNull(response)
        assertEquals("world", response!!.rpcResponse.payload)
        assertNull(collectOutgoingV2Stream(RPC_RESPONSE_DATA_STREAM_TOPIC))
    }

    @Test
    fun caller_to_v1_remote_rejects_large_payload() = runTest {
        connect()
        simulateRemoteJoinAsV1()

        var thrown: Throwable? = null
        try {
            room.localParticipant.performRpc(
                destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                method = "echo",
                payload = "X".repeat(20_000),
            )
        } catch (e: Throwable) {
            thrown = e
        }

        assertEquals(RpcError.BuiltinRpcError.REQUEST_PAYLOAD_TOO_LARGE.create(), thrown)
        // No packet or stream should have been produced.
        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        assertFalse(packets.any { it.hasRpcRequest() })
        assertNull(collectOutgoingV2Stream(RPC_REQUEST_DATA_STREAM_TOPIC))
    }

    /** Build a v1 `RpcRequest` packet from a v1 caller. */
    private fun v1RequestPacket(
        requestId: String,
        method: String,
        payload: String,
        responseTimeoutMs: Int = 10_000,
    ) = with(DataPacket.newBuilder()) {
        participantIdentity = REMOTE_PARTICIPANT.identity
        rpcRequest = with(LivekitModels.RpcRequest.newBuilder()) {
            this.id = requestId
            this.method = method
            this.payload = payload
            this.responseTimeoutMs = responseTimeoutMs
            this.version = RPC_VERSION_V1
            build()
        }
        build()
    }.toDataChannelBuffer()

    /**
     * Spec #18. Handler receives a v1 packet, handler throws a non-RpcError exception → v1
     * `RpcResponse` packet with `APPLICATION_ERROR`.
     */
    @Test
    fun v1_caller_handler_uncaught_exception_application_error_packet() = runTest {
        connect()
        simulateRemoteJoinAsV1()

        room.localParticipant.registerRpcMethod("boom") {
            throw RuntimeException("oops")
        }
        subDataChannel.simulateBufferReceived(v1RequestPacket("req-v1-app", "boom", ""))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        val errorResponse = packets.firstOrNull {
            it.hasRpcResponse() && it.rpcResponse.requestId == "req-v1-app" && it.rpcResponse.hasError()
        }
        assertNotNull(errorResponse)
        assertEquals(
            RpcError.BuiltinRpcError.APPLICATION_ERROR.create(),
            RpcError.fromProto(errorResponse!!.rpcResponse.error),
        )
    }

    /**
     * Spec #19. Handler receives a v1 packet, handler throws an `RpcError` → v1 `RpcResponse`
     * packet preserving the original code + message.
     */
    @Test
    fun v1_caller_handler_rpcerror_passthrough_packet() = runTest {
        connect()
        simulateRemoteJoinAsV1()

        val custom = RpcError(101, "custom v1 err")
        room.localParticipant.registerRpcMethod("err") { throw custom }
        subDataChannel.simulateBufferReceived(v1RequestPacket("req-v1-cust", "err", ""))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        val errorResponse = packets.firstOrNull {
            it.hasRpcResponse() && it.rpcResponse.requestId == "req-v1-cust" && it.rpcResponse.hasError()
        }
        assertNotNull(errorResponse)
        assertEquals(custom, RpcError.fromProto(errorResponse!!.rpcResponse.error))
    }

    /**
     * Spec #21. Caller targets a v1 remote (so it sends v1 `RpcRequest` packets) and the
     * response never arrives → `RESPONSE_TIMEOUT`.
     */
    @Test
    fun v1_caller_response_timeout() = runTest {
        connect()
        simulateRemoteJoinAsV1()

        val rpcJob = async {
            var thrown: Throwable? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "hello",
                    payload = "hi",
                )
            } catch (e: Throwable) {
                thrown = e
            }
            thrown
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        // Ack so we get past the connection-timeout window.
        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        val rpcRequest = packets.first { it.hasRpcRequest() }.rpcRequest
        subDataChannel.simulateBufferReceived(createAck(rpcRequest.id))

        coroutineRule.dispatcher.scheduler.advanceTimeBy(20_000)
        assertEquals(RpcError.BuiltinRpcError.RESPONSE_TIMEOUT.create(), rpcJob.await())
    }

    /** Spec #22. v1 caller receives an error `RpcResponse` packet → rejects with the error. */
    @Test
    fun v1_caller_error_response_via_packet() = runTest {
        connect()
        simulateRemoteJoinAsV1()

        val customError = RpcError(101, "v1 boom")
        val rpcJob = async {
            var thrown: Throwable? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "x",
                    payload = "p",
                )
            } catch (e: Throwable) {
                thrown = e
            }
            thrown
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        val packets = pubDataChannel.sentBuffers.map { parsePacket(it) }
        val rpcRequest = packets.first { it.hasRpcRequest() }.rpcRequest
        subDataChannel.simulateBufferReceived(createAck(rpcRequest.id))
        subDataChannel.simulateBufferReceived(createV1Response(rpcRequest.id, error = customError))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(customError, rpcJob.await())
    }

    /** Spec #23. v1 caller, remote disconnects mid-call → `RECIPIENT_DISCONNECTED`. */
    @Test
    fun v1_caller_participant_disconnect() = runTest {
        connect()
        simulateRemoteJoinAsV1()

        val rpcJob = async {
            var thrown: Throwable? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "x",
                    payload = "p",
                )
            } catch (e: Throwable) {
                thrown = e
            }
            thrown
        }
        coroutineRule.dispatcher.scheduler.runCurrent()

        simulateMessageFromServer(TestData.PARTICIPANT_DISCONNECT)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            RpcError.BuiltinRpcError.RECIPIENT_DISCONNECTED.create(),
            rpcJob.await(),
        )
    }
}
