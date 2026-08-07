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

package io.livekit.android.room

import livekit.LivekitModels

/**
 * An optional feature capability a client advertises at connect time.
 *
 * Capabilities are independent feature flags, in contrast to [ClientProtocolVersion], which is a
 * single monotonic version number. A client may speak a given client protocol yet still lack an
 * individual capability, so the two are negotiated separately.
 *
 * Advertised by this SDK during the join handshake, mirrored by the server onto every
 * `ParticipantInfo`, and readable per-peer via [io.livekit.android.room.participant.Participant.capabilities].
 */
enum class ClientCapability(val value: Int) {
    /**
     * The client can accept RTP packet trailers passed through by the SFU instead of having
     * them stripped.
     */
    PACKET_TRAILER(1),

    /**
     * The client can decompress a `deflate-raw` compressed data stream payload.
     */
    COMPRESSION_DEFLATE_RAW(2),
    ;

    fun toProto(): LivekitModels.ClientInfo.Capability {
        return when (this) {
            PACKET_TRAILER -> LivekitModels.ClientInfo.Capability.CAP_PACKET_TRAILER
            COMPRESSION_DEFLATE_RAW -> LivekitModels.ClientInfo.Capability.CAP_COMPRESSION_DEFLATE_RAW
        }
    }

    companion object {
        /**
         * Converts from the protobuf enum, returning null for values this SDK build does not
         * recognize.
         *
         * Unlike most `fromProto` helpers in this SDK this never throws: capabilities are an
         * open, forward-extensible set, so a peer or server advertising a newer capability must
         * be ignored rather than crash us.
         */
        fun fromProto(capability: LivekitModels.ClientInfo.Capability): ClientCapability? {
            return when (capability) {
                LivekitModels.ClientInfo.Capability.CAP_PACKET_TRAILER -> PACKET_TRAILER
                LivekitModels.ClientInfo.Capability.CAP_COMPRESSION_DEFLATE_RAW -> COMPRESSION_DEFLATE_RAW
                LivekitModels.ClientInfo.Capability.CAP_UNUSED,
                LivekitModels.ClientInfo.Capability.UNRECOGNIZED,
                -> null
            }
        }
    }
}

/**
 * The capabilities this SDK advertises to the server and, through it, to peers.
 *
 * Declared once so that every place which announces capabilities agrees. Data streams v2
 * compression is advertised unconditionally: deflate-raw is compressed and decompressed by the
 * Rust data stream core, so support does not vary by device, API level, or room options.
 *
 * @suppress
 */
internal val ADVERTISED_CLIENT_CAPABILITIES = listOf(
    ClientCapability.COMPRESSION_DEFLATE_RAW,
)
