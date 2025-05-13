/*
 * Copyright 2025 LiveKit, Inc.
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

import io.livekit.android.room.participant.Participant.Identity
import io.livekit.android.room.participant.RpcHandler
import io.livekit.android.room.participant.RpcInvocationData
import io.livekit.android.rpc.RpcError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface RpcManager {
    companion object {
        const val RPC_VERSION = 1
    }

    /**
     * Establishes the participant as a receiver for calls of the specified RPC method.
     * Will overwrite any existing callback for the same method.
     *
     * Example:
     * ```kt
     * room.registerRpcMethod("greet") { (requestId, callerIdentity, payload, responseTimeout) ->
     *     Log.i("TAG", "Received greeting from ${callerIdentity}: ${payload}")
     *
     *     // Return a string
     *     "Hello, ${callerIdentity}!"
     * }
     * ```
     *
     * The handler receives an [RpcInvocationData] with the following parameters:
     * - `requestId`: A unique identifier for this RPC request
     * - `callerIdentity`: The identity of the RemoteParticipant who initiated the RPC call
     * - `payload`: The data sent by the caller (as a string)
     * - `responseTimeout`: The maximum time available to return a response
     *
     * The handler should return a string.
     * If unable to respond within [RpcInvocationData.responseTimeout], the request will result in an error on the caller's side.
     *
     * You may throw errors of type [RpcError] with a string `message` in the handler,
     * and they will be received on the caller's side with the message intact.
     * Other errors thrown in your handler will not be transmitted as-is, and will instead arrive to the caller as `1500` ("Application Error").
     *
     * @param method The name of the indicated RPC method
     * @param handler Will be invoked when an RPC request for this method is received
     * @see RpcHandler
     * @see RpcInvocationData
     * @see performRpc
     */
    @Suppress("RedundantSuspendModifier")
    suspend fun registerRpcMethod(
        method: String,
        handler: RpcHandler,
    )

    /**
     * Unregisters a previously registered RPC method.
     *
     * @param method The name of the RPC method to unregister
     */
    fun unregisterRpcMethod(method: String)

    /**
     * Initiate an RPC call to a remote participant
     * @param destinationIdentity The identity of the destination participant.
     * @param method The method name to call.
     * @param payload The payload to pass to the method.
     * @param responseTimeout Timeout for receiving a response after initial connection.
     *      Defaults to 10000. Max value of UInt.MAX_VALUE milliseconds.
     * @return The response payload.
     * @throws RpcError on failure. Details in [RpcError.message].
     */
    suspend fun performRpc(
        destinationIdentity: Identity,
        method: String,
        payload: String,
        responseTimeout: Duration = 10.seconds,
    ): String
}
