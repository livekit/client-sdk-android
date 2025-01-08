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

package io.livekit.android.rpc

import io.livekit.android.room.RTCEngine
import io.livekit.android.util.truncateBytes
import livekit.LivekitModels

/**
 * Specialized error handling for RPC methods.
 *
 * Instances of this type, when thrown in a RPC method handler, will have their [message]
 * serialized and sent across the wire. The sender will receive an equivalent error on the other side.
 *
 * Built-in types are included but developers may use any message string, with a max length of 256 bytes.
 */
data class RpcError(
    /**
     * The error code of the RPC call. Error codes 1001-1999 are reserved for built-in errors.
     *
     * See [RpcError.BuiltinRpcError] for built-in error information.
     */
    val code: Int,

    /**
     * A message to include. Strings over 256 bytes will be truncated.
     */
    override val message: String,
    /**
     * An optional data payload. Must be smaller than 15KB in size, or else will be truncated.
     */
    val data: String = "",
) : Exception(message) {

    enum class BuiltinRpcError(val code: Int, val message: String) {
        APPLICATION_ERROR(1500, "Application error in method handler"),
        CONNECTION_TIMEOUT(1501, "Connection timeout"),
        RESPONSE_TIMEOUT(1502, "Response timeout"),
        RECIPIENT_DISCONNECTED(1503, "Recipient disconnected"),
        RESPONSE_PAYLOAD_TOO_LARGE(1504, "Response payload too large"),
        SEND_FAILED(1505, "Failed to send"),

        UNSUPPORTED_METHOD(1400, "Method not supported at destination"),
        RECIPIENT_NOT_FOUND(1401, "Recipient not found"),
        REQUEST_PAYLOAD_TOO_LARGE(1402, "Request payload too large"),
        UNSUPPORTED_SERVER(1403, "RPC not supported by server"),
        UNSUPPORTED_VERSION(1404, "Unsupported RPC version"),
        ;

        fun create(data: String = ""): RpcError {
            return RpcError(code, message, data)
        }
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 256

        fun fromProto(proto: LivekitModels.RpcError): RpcError {
            return RpcError(
                code = proto.code,
                message = (proto.message ?: "").truncateBytes(MAX_MESSAGE_BYTES),
                data = proto.data.truncateBytes(RTCEngine.MAX_DATA_PACKET_SIZE),
            )
        }
    }

    fun toProto(): LivekitModels.RpcError {
        return with(LivekitModels.RpcError.newBuilder()) {
            this.code = this@RpcError.code
            this.message = this@RpcError.message
            this.data = this@RpcError.data
            build()
        }
    }
}
