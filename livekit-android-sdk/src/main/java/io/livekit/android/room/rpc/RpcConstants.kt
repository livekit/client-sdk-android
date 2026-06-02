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

/** The maximum payload size for v1 RPC requests and responses in bytes. */
internal const val MAX_V1_PAYLOAD_BYTES = 15 * 1024 // 15KB

/** Version of RPC backed by inline `RpcRequest` / `RpcResponse` packets. */
internal const val RPC_VERSION_V1 = 1

/** Version of RPC backed by data streams for request and success-response payloads. */
internal const val RPC_VERSION_V2 = 2

internal const val RPC_REQUEST_DATA_STREAM_TOPIC = "lk.rpc_request"
internal const val RPC_RESPONSE_DATA_STREAM_TOPIC = "lk.rpc_response"

/** Attribute keys attached to v2 RPC request data streams. */
internal object RpcRequestAttrs {
    const val REQUEST_ID = "lk.rpc_request_id"
    const val METHOD = "lk.rpc_request_method"
    const val RESPONSE_TIMEOUT_MS = "lk.rpc_request_response_timeout_ms"
    const val VERSION = "lk.rpc_request_version"
}
