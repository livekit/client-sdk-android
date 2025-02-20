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

package io.livekit.android.room.participant

import com.google.protobuf.ByteString
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.rpc.RpcManager
import io.livekit.android.rpc.RpcError
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.TestData.REMOTE_PARTICIPANT
import io.livekit.android.test.util.toDataChannelBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import livekit.LivekitModels
import livekit.LivekitRtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RpcMockE2ETest : MockE2ETest() {

    lateinit var pubDataChannel: MockDataChannel
    lateinit var subDataChannel: MockDataChannel

    companion object {
        val ERROR = RpcError(
            1,
            "This is an error message.",
            "This is an error payload.",
        )
    }

    override suspend fun connect(joinResponse: LivekitRtc.SignalResponse) {
        super.connect(joinResponse)

        val pubPeerConnection = component.rtcEngine().getPublisherPeerConnection() as MockPeerConnection
        pubDataChannel = pubPeerConnection.dataChannels[RTCEngine.RELIABLE_DATA_CHANNEL_LABEL] as MockDataChannel

        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)
    }

    private fun createAck(requestId: String) =
        with(LivekitModels.DataPacket.newBuilder()) {
            participantIdentity = REMOTE_PARTICIPANT.identity
            rpcAck = with(LivekitModels.RpcAck.newBuilder()) {
                this.requestId = requestId
                build()
            }
            build()
        }.toDataChannelBuffer()

    private fun createResponse(requestId: String, payload: String? = null, error: RpcError? = null) = with(LivekitModels.DataPacket.newBuilder()) {
        participantIdentity = REMOTE_PARTICIPANT.identity
        rpcResponse = with(LivekitModels.RpcResponse.newBuilder()) {
            this.requestId = requestId
            if (error != null) {
                this.error = error.toProto()
            } else if (payload != null) {
                this.payload = payload
            }

            build()
        }
        build()
    }.toDataChannelBuffer()

    @Test
    fun handleRpcRequest() = runTest {
        connect()

        var methodCalled = false
        room.localParticipant.registerRpcMethod("hello") {
            methodCalled = true
            "bye"
        }
        subDataChannel.simulateBufferReceived(TestData.DATA_PACKET_RPC_REQUEST.toDataChannelBuffer())
        assertTrue(methodCalled)

        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        // Check that ack and response were sent
        val buffers = pubDataChannel.sentBuffers
        assertEquals(2, buffers.size)

        val ackBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))
        val responseBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[1].data))

        assertTrue(ackBuffer.hasRpcAck())
        assertEquals(TestData.DATA_PACKET_RPC_REQUEST.rpcRequest.id, ackBuffer.rpcAck.requestId)

        assertTrue(responseBuffer.hasRpcResponse())
        assertEquals(TestData.DATA_PACKET_RPC_REQUEST.rpcRequest.id, responseBuffer.rpcResponse.requestId)
        assertEquals("bye", responseBuffer.rpcResponse.payload)
    }

    @Test
    fun handleRpcRequestWithError() = runTest {
        connect()

        var methodCalled = false
        room.localParticipant.registerRpcMethod("hello") {
            methodCalled = true
            throw ERROR
        }
        subDataChannel.simulateBufferReceived(TestData.DATA_PACKET_RPC_REQUEST.toDataChannelBuffer())
        assertTrue(methodCalled)

        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        // Check that ack and response were sent
        val buffers = pubDataChannel.sentBuffers
        assertEquals(2, buffers.size)

        val ackBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))
        val responseBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[1].data))

        assertTrue(ackBuffer.hasRpcAck())
        assertEquals(TestData.DATA_PACKET_RPC_REQUEST.rpcRequest.id, ackBuffer.rpcAck.requestId)

        assertTrue(responseBuffer.hasRpcResponse())
        assertEquals(TestData.DATA_PACKET_RPC_REQUEST.rpcRequest.id, responseBuffer.rpcResponse.requestId)
        assertEquals(ERROR, RpcError.fromProto(responseBuffer.rpcResponse.error))
    }

    @Test
    fun handleRpcRequestWithNoVersion() = runTest {
        connect()

        var methodCalled = false
        room.localParticipant.registerRpcMethod("hello") {
            methodCalled = true
            return@registerRpcMethod "hello back"
        }

        val noVersionRequest = with(TestData.DATA_PACKET_RPC_REQUEST.toBuilder()) {
            rpcRequest = with(rpcRequest.toBuilder()) {
                clearVersion()
                build()
            }
            build()
        }
        subDataChannel.simulateBufferReceived(noVersionRequest.toDataChannelBuffer())

        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse(methodCalled)
        // Check that ack and response were sent
        val buffers = pubDataChannel.sentBuffers
        assertEquals(2, buffers.size)

        val ackBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))
        val responseBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[1].data))

        assertTrue(ackBuffer.hasRpcAck())
        assertEquals(TestData.DATA_PACKET_RPC_REQUEST.rpcRequest.id, ackBuffer.rpcAck.requestId)

        assertTrue(responseBuffer.hasRpcResponse())
        assertEquals(TestData.DATA_PACKET_RPC_REQUEST.rpcRequest.id, responseBuffer.rpcResponse.requestId)
        assertEquals(RpcError.BuiltinRpcError.UNSUPPORTED_VERSION.create(), RpcError.fromProto(responseBuffer.rpcResponse.error))
    }

    @Test
    fun handleRpcRequestWithNoHandler() = runTest {
        connect()

        subDataChannel.simulateBufferReceived(TestData.DATA_PACKET_RPC_REQUEST.toDataChannelBuffer())

        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        // Check that ack and response were sent
        val buffers = pubDataChannel.sentBuffers
        assertEquals(2, buffers.size)

        val ackBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))
        val responseBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[1].data))

        assertTrue(ackBuffer.hasRpcAck())
        assertEquals(TestData.DATA_PACKET_RPC_REQUEST.rpcRequest.id, ackBuffer.rpcAck.requestId)

        assertTrue(responseBuffer.hasRpcResponse())
        assertEquals(TestData.DATA_PACKET_RPC_REQUEST.rpcRequest.id, responseBuffer.rpcResponse.requestId)
        assertEquals(RpcError.BuiltinRpcError.UNSUPPORTED_METHOD.create(), RpcError.fromProto(responseBuffer.rpcResponse.error))
    }

    @Test
    fun performRpc() = runTest {
        connect()

        val rpcJob = async {
            room.localParticipant.performRpc(
                destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                method = "hello",
                payload = "hello world",
            )
        }

        // Check that request was sent
        val buffers = pubDataChannel.sentBuffers
        assertEquals(1, buffers.size)

        val requestBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))

        assertTrue(requestBuffer.hasRpcRequest())
        assertEquals("hello", requestBuffer.rpcRequest.method)
        assertEquals("hello world", requestBuffer.rpcRequest.payload)
        assertEquals(RpcManager.RPC_VERSION, requestBuffer.rpcRequest.version)

        val requestId = requestBuffer.rpcRequest.id

        // receive ack and response
        subDataChannel.simulateBufferReceived(createAck(requestId))
        subDataChannel.simulateBufferReceived(createResponse(requestId, payload = "bye"))

        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        val response = rpcJob.await()

        assertEquals("bye", response)
    }

    @Test
    fun performRpcWithError() = runTest {
        connect()

        val rpcJob = async {
            var expectedError: Exception? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "hello",
                    payload = "hello world",
                )
            } catch (e: Exception) {
                expectedError = e
            }
            return@async expectedError
        }

        val buffers = pubDataChannel.sentBuffers
        val requestBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))
        val requestId = requestBuffer.rpcRequest.id

        // receive ack and response
        subDataChannel.simulateBufferReceived(createAck(requestId))
        subDataChannel.simulateBufferReceived(createResponse(requestId, error = ERROR))

        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        val receivedError = rpcJob.await()

        assertEquals(ERROR, receivedError)
    }

    @Test
    fun performRpcWithParticipantDisconnected() = runTest {
        connect()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)

        val rpcJob = async {
            var expectedError: Exception? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "hello",
                    payload = "hello world",
                )
            } catch (e: Exception) {
                expectedError = e
            }
            return@async expectedError
        }

        simulateMessageFromServer(TestData.PARTICIPANT_DISCONNECT)

        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        val error = rpcJob.await()

        assertEquals(RpcError.BuiltinRpcError.RECIPIENT_DISCONNECTED.create(), error)
    }

    @Test
    fun performRpcWithConnectionTimeoutError() = runTest {
        connect()

        val rpcJob = async {
            var expectedError: Exception? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "hello",
                    payload = "hello world",
                )
            } catch (e: Exception) {
                expectedError = e
            }
            return@async expectedError
        }

        coroutineRule.dispatcher.scheduler.advanceTimeBy(3000)

        val error = rpcJob.await()

        assertEquals(RpcError.BuiltinRpcError.CONNECTION_TIMEOUT.create(), error)
    }

    @Test
    fun performRpcWithResponseTimeoutError() = runTest {
        connect()

        val rpcJob = async {
            var expectedError: Exception? = null
            try {
                room.localParticipant.performRpc(
                    destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                    method = "hello",
                    payload = "hello world",
                )
            } catch (e: Exception) {
                expectedError = e
            }
            return@async expectedError
        }

        val buffers = pubDataChannel.sentBuffers
        val requestBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))
        val requestId = requestBuffer.rpcRequest.id

        // receive ack only
        subDataChannel.simulateBufferReceived(createAck(requestId))

        coroutineRule.dispatcher.scheduler.advanceTimeBy(15000)

        val error = rpcJob.await()

        assertEquals(RpcError.BuiltinRpcError.RESPONSE_TIMEOUT.create(), error)
    }

    @Test
    fun uintMaxValueVerification() = runTest {
        assertEquals(4_294_967_295L, UInt.MAX_VALUE.toLong())
    }

    /**
     * Protobuf handles UInt32 as Java signed integers.
     * This test verifies whether our conversion is properly sent over the wire.
     */
    @Test
    fun performRpcProtoUIntVerification() = runTest {
        connect()
        val rpcJob = launch {
            room.localParticipant.performRpc(
                destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                method = "hello",
                payload = "hello world",
                responseTimeout = UInt.MAX_VALUE.toLong().milliseconds,
            )
        }

        val buffers = pubDataChannel.sentBuffers
        val requestBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))

        val expectedResponseTimeout = UInt.MAX_VALUE - 2000u // 2000 comes from maxRoundTripLatency
        val responseTimeout = requestBuffer.rpcRequest.responseTimeoutMs.toUInt()
        assertEquals(expectedResponseTimeout, responseTimeout)
        rpcJob.cancel()
    }

    /**
     * Protobuf handles UInt32 as Java signed integers.
     * This test verifies whether our conversion is properly sent over the wire.
     */
    @Test
    fun handleRpcProtoUIntVerification() = runTest {
        connect()

        var methodCalled = false
        room.localParticipant.registerRpcMethod("hello") { invocationData ->
            assertEquals(4_294_967_295L, invocationData.responseTimeout.inWholeMilliseconds)
            methodCalled = true
            "bye"
        }
        subDataChannel.simulateBufferReceived(
            with(TestData.DATA_PACKET_RPC_REQUEST.toBuilder()) {
                rpcRequest = with(rpcRequest.toBuilder()) {
                    responseTimeoutMs = UInt.MAX_VALUE.toInt()
                    build()
                }
                build()
            }.toDataChannelBuffer(),
        )
        assertTrue(methodCalled)
    }
}
