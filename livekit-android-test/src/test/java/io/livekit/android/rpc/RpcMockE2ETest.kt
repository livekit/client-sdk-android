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
import io.livekit.android.rpc.RpcError
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.TestData.REMOTE_PARTICIPANT
import io.livekit.android.test.util.toDataChannelBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.LivekitModels
import livekit.LivekitRtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RpcMockE2ETest : MockE2ETest() {

    lateinit var pubDataChannel: MockDataChannel
    lateinit var subDataChannel: MockDataChannel

    override suspend fun connect(joinResponse: LivekitRtc.SignalResponse) {
        super.connect(joinResponse)

        val pubPeerConnection = component.rtcEngine().getPublisherPeerConnection() as MockPeerConnection
        pubDataChannel = pubPeerConnection.dataChannels[RTCEngine.RELIABLE_DATA_CHANNEL_LABEL] as MockDataChannel

        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)
    }

    @Test
    fun handleRpcRequest() = runTest {
        connect()

        var methodCalled = false
        room.localParticipant.registerRpcMethod("hello") { _, _, _, _ ->
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
    fun performRpc() = runTest {
        connect()

        val rpcJob = async(Dispatchers.Default) {
            room.localParticipant.performRpc(
                destinationIdentity = Participant.Identity(REMOTE_PARTICIPANT.identity),
                method = "hello",
                payload = "hello world",
            )
        }

        val wait = launch(Dispatchers.Default) {
            delay(200L)
        }
        wait.join()

        // Check that request was sent
        val buffers = pubDataChannel.sentBuffers
        assertEquals(1, buffers.size)

        val requestBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))

        assertTrue(requestBuffer.hasRpcRequest())
        assertEquals("hello", requestBuffer.rpcRequest.method)
        assertEquals("hello world", requestBuffer.rpcRequest.payload)

        val requestId = requestBuffer.rpcRequest.id

        // send ack
        subDataChannel.simulateBufferReceived(
            with(LivekitModels.DataPacket.newBuilder()) {
                participantIdentity = REMOTE_PARTICIPANT.identity
                rpcAck = with(LivekitModels.RpcAck.newBuilder()) {
                    this.requestId = requestId
                    build()
                }
                build()
            }.toDataChannelBuffer(),
        )
        // send response
        subDataChannel.simulateBufferReceived(
            with(LivekitModels.DataPacket.newBuilder()) {
                participantIdentity = REMOTE_PARTICIPANT.identity
                rpcResponse = with(LivekitModels.RpcResponse.newBuilder()) {
                    this.requestId = requestId
                    this.payload = "bye"
                    build()
                }
                build()
            }.toDataChannelBuffer(),
        )

        val response = rpcJob.await()

        assertEquals("bye", response)
    }

    @Test
    fun performRpcWithError() = runTest {
        connect()

        val rpcJob = async(Dispatchers.Default) {
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

        val wait = launch(Dispatchers.Default) {
            delay(200L)
        }
        wait.join()

        val buffers = pubDataChannel.sentBuffers
        val requestBuffer = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffers[0].data))
        val requestId = requestBuffer.rpcRequest.id

        // send ack
        subDataChannel.simulateBufferReceived(
            with(LivekitModels.DataPacket.newBuilder()) {
                participantIdentity = REMOTE_PARTICIPANT.identity
                rpcAck = with(LivekitModels.RpcAck.newBuilder()) {
                    this.requestId = requestId
                    build()
                }
                build()
            }.toDataChannelBuffer(),
        )
        // send response
        subDataChannel.simulateBufferReceived(
            with(LivekitModels.DataPacket.newBuilder()) {
                participantIdentity = REMOTE_PARTICIPANT.identity
                rpcResponse = with(LivekitModels.RpcResponse.newBuilder()) {
                    this.requestId = requestId
                    this.error = RpcError.BuiltinRpcError.UNSUPPORTED_METHOD.create().toProto()
                    build()
                }
                build()
            }.toDataChannelBuffer(),
        )

        val error = rpcJob.await()

        assertEquals(RpcError.BuiltinRpcError.UNSUPPORTED_METHOD.create(), error)
    }
}
