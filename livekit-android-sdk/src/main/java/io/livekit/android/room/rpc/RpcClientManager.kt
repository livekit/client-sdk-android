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
import com.vdurmont.semver4j.Semver
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.datastream.incoming.TextStreamReceiver
import io.livekit.android.room.datastream.outgoing.OutgoingDataStreamManager
import io.livekit.android.room.datastream.outgoing.useStreamSender
import io.livekit.android.room.participant.Participant.Identity
import io.livekit.android.rpc.RpcError
import io.livekit.android.util.LKLog
import io.livekit.android.util.byteLength
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.LivekitModels
import livekit.LivekitModels.DataPacket
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Manages the **caller** (client) side of RPC:
 *  - issuing `performRpc` calls,
 *  - tracking pending acks and responses by request id,
 *  - routing incoming acks / responses (whether via v1 packet or v2 data stream) to the
 *    awaiting coroutines.
 *
 * Lives on `Room` (one instance per room session, injected by Dagger).
 *
 * @suppress
 */
@Singleton
class RpcClientManager @Inject constructor(
    private val engine: RTCEngine,
    private val outgoingDataStreamManager: OutgoingDataStreamManager,
) {

    /**
     * Late-bound: returns the advertised `clientProtocol` of the remote participant with the
     * given identity, or [CLIENT_PROTOCOL_DEFAULT] if unknown. Set by `Room` once the
     * participant store is available.
     */
    var getRemoteClientProtocol: (Identity) -> Int = { CLIENT_PROTOCOL_DEFAULT }

    private val pendingAcks = Collections.synchronizedMap(mutableMapOf<String, PendingRpcAck>())
    private val pendingResponses = Collections.synchronizedMap(mutableMapOf<String, PendingRpcResponse>())

    suspend fun performRpc(
        destinationIdentity: Identity,
        method: String,
        payload: String,
        responseTimeout: Duration,
    ): String = coroutineScope {
        // Maximum amount of time it should ever take for an RPC request to reach the destination,
        // and the ACK to come back. Set to 7s to cover Cloud relay retries.
        val maxRoundTripLatency = 7.seconds
        // Minimum allowed effective response timeout after subtracting round-trip latency.
        val minEffectiveTimeout = 1.seconds

        val remoteClientProtocol = getRemoteClientProtocol(destinationIdentity)

        // The 15 KB packet limit only applies to v1; v2 streams chunk transparently.
        if (remoteClientProtocol < CLIENT_PROTOCOL_DATA_STREAM_RPC &&
            payload.byteLength() > RTCEngine.MAX_DATA_PACKET_SIZE
        ) {
            throw RpcError.BuiltinRpcError.REQUEST_PAYLOAD_TOO_LARGE.create()
        }

        val serverVersion = engine.serverVersion
            ?: throw RpcError.BuiltinRpcError.SEND_FAILED.create(data = "Not connected.")
        if (serverVersion < Semver("1.8.0")) {
            throw RpcError.BuiltinRpcError.UNSUPPORTED_SERVER.create()
        }

        val requestId = UUID.randomUUID().toString()
        val effectiveTimeout = (responseTimeout - maxRoundTripLatency).coerceAtLeast(minEffectiveTimeout)

        val responseDeferred = CompletableDeferred<String>()

        val ackTimeoutJob = launch {
            delay(maxRoundTripLatency)
            if (pendingAcks.remove(requestId) != null) {
                pendingResponses.remove(requestId)
                responseDeferred.completeExceptionally(
                    RpcError.BuiltinRpcError.CONNECTION_TIMEOUT.create(),
                )
            }
        }

        val responseTimeoutJob = launch {
            delay(responseTimeout)
            if (pendingResponses.remove(requestId) != null) {
                responseDeferred.completeExceptionally(
                    RpcError.BuiltinRpcError.RESPONSE_TIMEOUT.create(),
                )
            }
        }

        // Register pending state BEFORE the suspending publish so a response that arrives
        // during the publish path is routed correctly (spec #12).
        pendingAcks[requestId] = PendingRpcAck(destinationIdentity) {
            ackTimeoutJob.cancel()
        }
        pendingResponses[requestId] = PendingRpcResponse(destinationIdentity) { responsePayload, error ->
            if (pendingAcks.remove(requestId) != null) {
                LKLog.i { "RPC response received before ack, id: $requestId" }
                ackTimeoutJob.cancel()
            }
            responseTimeoutJob.cancel()
            if (error != null) {
                responseDeferred.completeExceptionally(error)
            } else {
                responseDeferred.complete(responsePayload ?: "")
            }
        }

        val publishResult = if (remoteClientProtocol >= CLIENT_PROTOCOL_DATA_STREAM_RPC) {
            publishRpcRequestV2(destinationIdentity, requestId, method, payload, effectiveTimeout.inWholeMilliseconds)
        } else {
            publishRpcRequestV1(destinationIdentity, requestId, method, payload, effectiveTimeout)
        }

        if (publishResult.isFailure) {
            pendingAcks.remove(requestId)
            pendingResponses.remove(requestId)
            ackTimeoutJob.cancel()
            responseTimeoutJob.cancel()
            val cause = publishResult.exceptionOrNull()
            val exception = cause as? RpcError
                ?: RpcError.BuiltinRpcError.SEND_FAILED.create(
                    data = "Error while sending rpc request.",
                    cause = cause,
                )
            throw exception
        }

        try {
            responseDeferred.await()
        } finally {
            pendingAcks.remove(requestId)
            pendingResponses.remove(requestId)
            ackTimeoutJob.cancel()
            responseTimeoutJob.cancel()
        }
    }

    @CheckResult
    private suspend fun publishRpcRequestV1(
        destinationIdentity: Identity,
        requestId: String,
        method: String,
        payload: String,
        responseTimeout: Duration,
    ): Result<Unit> {
        val dataPacket = with(DataPacket.newBuilder()) {
            addDestinationIdentities(destinationIdentity.value)
            kind = DataPacket.Kind.RELIABLE
            rpcRequest = with(LivekitModels.RpcRequest.newBuilder()) {
                this.id = requestId
                this.method = method
                this.payload = payload
                this.responseTimeoutMs = responseTimeout.inWholeMilliseconds.toUInt().toInt()
                this.version = RPC_VERSION_V1
                build()
            }
            build()
        }
        return rpcSendData(dataPacket)
    }

    @CheckResult
    private suspend fun publishRpcRequestV2(
        destinationIdentity: Identity,
        requestId: String,
        method: String,
        payload: String,
        effectiveTimeoutMs: Long,
    ): Result<Unit> {
        val sender = try {
            outgoingDataStreamManager.streamText(
                StreamTextOptions(
                    topic = RPC_REQUEST_DATA_STREAM_TOPIC,
                    destinationIdentities = listOf(destinationIdentity),
                    attributes = mapOf(
                        RpcRequestAttrs.REQUEST_ID to requestId,
                        RpcRequestAttrs.METHOD to method,
                        RpcRequestAttrs.RESPONSE_TIMEOUT_MS to effectiveTimeoutMs.toString(),
                        RpcRequestAttrs.VERSION to RPC_VERSION_V2.toString(),
                    ),
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

    fun handleIncomingRpcAck(requestId: String) {
        val handler = pendingAcks.remove(requestId)
        if (handler != null) {
            handler.onResolve()
        } else {
            LKLog.e { "Ack received for unexpected RPC request, id = $requestId" }
        }
    }

    fun handleIncomingRpcResponseSuccess(requestId: String, payload: String) {
        val handler = pendingResponses.remove(requestId)
        if (handler != null) {
            handler.onResolve(payload, null)
        } else {
            LKLog.e { "Response received for unexpected RPC request, id = $requestId" }
        }
    }

    fun handleIncomingRpcResponseFailure(requestId: String, error: RpcError) {
        val handler = pendingResponses.remove(requestId)
        if (handler != null) {
            handler.onResolve(null, error)
        } else {
            LKLog.e { "Error response received for unexpected RPC request, id = $requestId" }
        }
    }

    /**
     * Handle an incoming v2 RPC response data stream on topic `lk.rpc_response`.
     */
    suspend fun handleIncomingDataStream(receiver: TextStreamReceiver, fromIdentity: Identity) {
        val requestId = receiver.info.attributes[RpcRequestAttrs.REQUEST_ID]
        if (requestId.isNullOrEmpty()) {
            LKLog.w { "RPC response stream malformed: ${RpcRequestAttrs.REQUEST_ID} not set." }
            return
        }

        // Validate sender identity matches the expected destination of the pending request.
        // (Spec #14: a response data stream from an unexpected sender MUST NOT resolve the
        // pending request.)
        val pending = pendingResponses[requestId]
        if (pending != null && pending.participantIdentity != fromIdentity) {
            LKLog.w {
                "RPC response stream for $requestId arrived from unexpected sender " +
                    "${fromIdentity.value}, expected ${pending.participantIdentity.value}. Ignoring."
            }
            return
        }

        val payload = try {
            receiver.readAll().joinToString(separator = "")
        } catch (e: Throwable) {
            LKLog.w(e) { "Error reading RPC response payload for $requestId" }
            handleIncomingRpcResponseFailure(
                requestId,
                RpcError.BuiltinRpcError.APPLICATION_ERROR.create(
                    data = "Error reading RPC response payload",
                    cause = e,
                ),
            )
            return
        }

        handleIncomingRpcResponseSuccess(requestId, payload)
    }

    fun handleParticipantDisconnect(identity: Identity) {
        synchronized(pendingAcks) {
            val acksIterator = pendingAcks.iterator()
            while (acksIterator.hasNext()) {
                val (_, ack) = acksIterator.next()
                if (ack.participantIdentity == identity) {
                    acksIterator.remove()
                }
            }
        }

        synchronized(pendingResponses) {
            val responsesIterator = pendingResponses.iterator()
            while (responsesIterator.hasNext()) {
                val (_, response) = responsesIterator.next()
                if (response.participantIdentity == identity) {
                    responsesIterator.remove()
                    response.onResolve(null, RpcError.BuiltinRpcError.RECIPIENT_DISCONNECTED.create())
                }
            }
        }
    }

    private data class PendingRpcAck(
        val participantIdentity: Identity,
        val onResolve: () -> Unit,
    )

    private data class PendingRpcResponse(
        val participantIdentity: Identity,
        val onResolve: (payload: String?, error: RpcError?) -> Unit,
    )
}
