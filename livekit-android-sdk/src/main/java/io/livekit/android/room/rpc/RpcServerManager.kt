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

import androidx.annotation.CheckResult
import io.livekit.android.room.ClientProtocolVersion
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.datastream.incoming.TextStreamReceiver
import io.livekit.android.room.datastream.outgoing.OutgoingDataStreamManager
import io.livekit.android.room.datastream.outgoing.useStreamSender
import io.livekit.android.room.participant.Participant.Identity
import io.livekit.android.room.participant.RpcHandler
import io.livekit.android.room.participant.RpcInvocationData
import io.livekit.android.rpc.RpcError
import io.livekit.android.util.LKLog
import io.livekit.android.util.byteLength
import io.livekit.android.util.rethrowIfCancellationSignal
import livekit.LivekitModels
import livekit.LivekitModels.DataPacket
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the **handler** (server) side of RPC:
 *  - the registered method handlers,
 *  - dispatching v1 (`RpcRequest` packet) and v2 (data-stream) incoming requests through to
 *    the handlers, and
 *  - sending the response back as either a v1 packet (errors, or v1 callers) or a v2 data
 *    stream (success responses to v2 callers).
 *
 * Lives on `Room` (one instance per room session, injected by Dagger).
 *
 * @suppress
 */
@Singleton
class RpcServerManager @Inject constructor(
    private val engine: RTCEngine,
    private val outgoingDataStreamManager: OutgoingDataStreamManager,
) {

    /**
     * Late-bound: returns the advertised `clientProtocol` of the remote participant with the
     * given identity, or [ClientProtocolVersion.DEFAULT] if unknown. Set by `Room` once the
     * participant store is available.
     */
    var getRemoteClientProtocol: (Identity) -> Int = { ClientProtocolVersion.DEFAULT.value }

    private val rpcHandlers = Collections.synchronizedMap(mutableMapOf<String, RpcHandler>())

    fun registerRpcMethod(method: String, handler: RpcHandler) {
        rpcHandlers[method] = handler
    }

    fun unregisterRpcMethod(method: String) {
        rpcHandlers.remove(method)
    }

    /**
     * Handle an incoming v1 `RpcRequest` packet.
     */
    suspend fun handleIncomingRpcRequest(
        callerIdentity: Identity,
        rpcRequest: LivekitModels.RpcRequest,
    ) {
        val requestId = rpcRequest.id
        val responseTimeout = rpcRequest.responseTimeoutMs.toUInt().toLong().milliseconds

        publishRpcAck(callerIdentity, requestId).also { result ->
            if (result.isFailure) {
                LKLog.w(result.exceptionOrNull()) { "Error sending ack for request $requestId." }
                return
            }
        }

        if (rpcRequest.version != RPC_VERSION_V1) {
            sendErrorResponse(callerIdentity, requestId, RpcError.BuiltinRpcError.UNSUPPORTED_VERSION.create())
            return
        }

        runHandlerAndSendResponse(
            callerIdentity = callerIdentity,
            requestId = requestId,
            method = rpcRequest.method,
            payload = rpcRequest.payload,
            responseTimeoutMs = responseTimeout.inWholeMilliseconds,
        )
    }

    /**
     * Handle an incoming v2 RPC request data stream on topic `lk.rpc_request`.
     */
    suspend fun handleIncomingDataStream(
        receiver: TextStreamReceiver,
        callerIdentity: Identity,
    ) {
        val attrs = receiver.info.attributes
        val requestId = attrs[RpcRequestAttrs.REQUEST_ID]
        val method = attrs[RpcRequestAttrs.METHOD]
        val responseTimeoutMs = attrs[RpcRequestAttrs.RESPONSE_TIMEOUT_MS]?.toLongOrNull()
        val version = attrs[RpcRequestAttrs.VERSION]?.toIntOrNull()

        if (requestId.isNullOrEmpty()) {
            LKLog.w { "RPC v2 request stream malformed: ${RpcRequestAttrs.REQUEST_ID} not set." }
            return
        }

        if (method.isNullOrEmpty() || responseTimeoutMs == null || version == null) {
            LKLog.w {
                "RPC v2 request stream malformed for $requestId: " +
                    "method=$method, responseTimeoutMs=$responseTimeoutMs, version=$version"
            }
            publishRpcAck(callerIdentity, requestId)
            sendErrorResponse(
                callerIdentity,
                requestId,
                RpcError.BuiltinRpcError.APPLICATION_ERROR.create(data = "RPC request stream malformed"),
            )
            return
        }

        publishRpcAck(callerIdentity, requestId).also { result ->
            if (result.isFailure) {
                LKLog.w(result.exceptionOrNull()) { "Error sending ack for request $requestId." }
                return
            }
        }

        if (version != RPC_VERSION_V2) {
            sendErrorResponse(callerIdentity, requestId, RpcError.BuiltinRpcError.UNSUPPORTED_VERSION.create())
            return
        }

        val payload = try {
            receiver.readAll().joinToString(separator = "")
        } catch (e: Throwable) {
            LKLog.w(e) { "Error reading RPC request payload for $requestId" }
            sendErrorResponse(
                callerIdentity,
                requestId,
                RpcError.BuiltinRpcError.APPLICATION_ERROR.create(
                    data = "Error reading RPC request payload",
                    cause = e,
                ),
            )
            return
        }

        runHandlerAndSendResponse(
            callerIdentity = callerIdentity,
            requestId = requestId,
            method = method,
            payload = payload,
            responseTimeoutMs = responseTimeoutMs,
        )
    }

    private suspend fun runHandlerAndSendResponse(
        callerIdentity: Identity,
        requestId: String,
        method: String,
        payload: String,
        responseTimeoutMs: Long,
    ) {
        val handler = rpcHandlers[method]
        if (handler == null) {
            sendErrorResponse(callerIdentity, requestId, RpcError.BuiltinRpcError.UNSUPPORTED_METHOD.create())
            return
        }

        val response: String = try {
            handler.invoke(
                RpcInvocationData(
                    requestId = requestId,
                    callerIdentity = callerIdentity,
                    payload = payload,
                    responseTimeout = responseTimeoutMs.milliseconds,
                ),
            )
        } catch (e: Throwable) {
            e.rethrowIfCancellationSignal()
            val responseError = if (e is RpcError) {
                e
            } else {
                LKLog.w(e) {
                    "Uncaught error returned by RPC handler for $method. Returning APPLICATION_ERROR instead."
                }
                RpcError.BuiltinRpcError.APPLICATION_ERROR.create()
            }
            sendErrorResponse(callerIdentity, requestId, responseError)
            return
        }

        sendSuccessResponse(callerIdentity, requestId, response)
    }

    /**
     * Send a successful RPC response. Chooses v2 (data stream) when the caller advertises
     * [ClientProtocolVersion.DATA_STREAM_RPC] or higher, otherwise sends a v1 packet (and
     * enforces the 15 KB size cap, since v1 has no chunking).
     */
    private suspend fun sendSuccessResponse(
        callerIdentity: Identity,
        requestId: String,
        payload: String,
    ) {
        val callerProtocol = getRemoteClientProtocol(callerIdentity)

        if (callerProtocol >= ClientProtocolVersion.DATA_STREAM_RPC.value) {
            val publishResult = publishRpcResponseV2(callerIdentity, requestId, payload)
            if (publishResult.isFailure) {
                LKLog.w(publishResult.exceptionOrNull()) {
                    "Error sending v2 response stream for $requestId"
                }
            }
            return
        }

        // v1 caller: payload-size guard required (no chunking).
        if (payload.byteLength() > RTCEngine.MAX_DATA_PACKET_SIZE) {
            LKLog.w { "RPC v1 response payload too large for request $requestId." }
            sendErrorResponse(
                callerIdentity,
                requestId,
                RpcError.BuiltinRpcError.RESPONSE_PAYLOAD_TOO_LARGE.create(),
            )
            return
        }

        publishRpcResponseV1(callerIdentity, requestId, payload, null).also { result ->
            if (result.isFailure) {
                LKLog.w(result.exceptionOrNull()) { "Error sending response for request $requestId." }
            }
        }
    }

    /**
     * Error responses always travel as v1 packets, regardless of either side's protocol.
     */
    private suspend fun sendErrorResponse(
        callerIdentity: Identity,
        requestId: String,
        error: RpcError,
    ) {
        publishRpcResponseV1(callerIdentity, requestId, null, error).also { result ->
            if (result.isFailure) {
                LKLog.w(result.exceptionOrNull()) {
                    "Error sending error response for request $requestId."
                }
            }
        }
    }

    @CheckResult
    private suspend fun publishRpcResponseV1(
        callerIdentity: Identity,
        requestId: String,
        payload: String?,
        error: RpcError?,
    ): Result<Unit> {
        val dataPacket = with(DataPacket.newBuilder()) {
            addDestinationIdentities(callerIdentity.value)
            kind = DataPacket.Kind.RELIABLE
            rpcResponse = with(LivekitModels.RpcResponse.newBuilder()) {
                this.requestId = requestId
                if (error != null) {
                    this.error = error.toProto()
                } else {
                    this.payload = payload ?: ""
                }
                build()
            }
            build()
        }
        return rpcSendData(dataPacket)
    }

    @CheckResult
    private suspend fun publishRpcResponseV2(
        callerIdentity: Identity,
        requestId: String,
        payload: String,
    ): Result<Unit> {
        val sender = try {
            outgoingDataStreamManager.streamText(
                StreamTextOptions(
                    topic = RPC_RESPONSE_DATA_STREAM_TOPIC,
                    destinationIdentities = listOf(callerIdentity),
                    attributes = mapOf(RpcRequestAttrs.REQUEST_ID to requestId),
                ),
            )
        } catch (e: Throwable) {
            return Result.failure(e)
        }
        return useStreamSender(sender) {
            write(payload).getOrThrow()
            close()
        }
    }

    @CheckResult
    private suspend fun publishRpcAck(
        callerIdentity: Identity,
        requestId: String,
    ): Result<Unit> {
        val dataPacket = with(DataPacket.newBuilder()) {
            addDestinationIdentities(callerIdentity.value)
            kind = DataPacket.Kind.RELIABLE
            rpcAck = with(LivekitModels.RpcAck.newBuilder()) {
                this.requestId = requestId
                build()
            }
            build()
        }
        return rpcSendData(dataPacket)
    }

    @CheckResult
    private suspend fun rpcSendData(dataPacket: DataPacket): Result<Unit> {
        val result = engine.sendData(dataPacket)
        return if (result.isFailure) {
            Result.failure(
                RpcError.BuiltinRpcError.SEND_FAILED.create(cause = result.exceptionOrNull()),
            )
        } else {
            result
        }
    }
}
