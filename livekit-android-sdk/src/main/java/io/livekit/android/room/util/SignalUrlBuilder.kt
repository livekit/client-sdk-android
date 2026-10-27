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

package io.livekit.android.room.util

import com.google.protobuf.ByteString
import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.stats.NetworkType
import io.livekit.android.stats.getClientInfo
import io.livekit.android.util.toHttpUrl
import io.livekit.android.util.toWebsocketUrl
import livekit.LivekitModels.ReconnectReason
import livekit.LivekitRtc.ConnectionSettings
import livekit.LivekitRtc.JoinRequest
import livekit.LivekitRtc.SessionDescription
import livekit.LivekitRtc.WrappedJoinRequest
import livekit.LivekitRtc.WrappedJoinRequest.Compression
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrl as parseHttpUrl

/**
 * Builds signal connection URLs for the `/rtc/v1` endpoint, where all join parameters
 * are carried in a single `join_request` query parameter holding a base64url-encoded
 * [WrappedJoinRequest].
 */
internal object SignalUrlBuilder {

    const val QUERY_JOIN_REQUEST = "join_request"

    /**
     * Creates the websocket URL `<url>/rtc/v1?join_request=<encoded>`.
     *
     * [url] may use an http(s) or ws(s) scheme and may include a path prefix. A trailing
     * `rtc` or `validate` path segment is replaced rather than appended to.
     *
     * The join request is gzipped only when that makes it smaller, and is encoded with the
     * URL-safe base64 alphabet, keeping padding.
     *
     * @param connectOptions also decides whether this is a reconnect, and with which participant.
     * @param publisherOffer bundled into the join request so the server can answer it
     * in the same exchange.
     * @return the websocket URL, or a failure if [url] cannot be parsed.
     */
    fun buildJoinRequestUrl(
        url: String,
        connectOptions: ConnectOptions,
        roomOptions: RoomOptions,
        networkType: NetworkType? = null,
        publisherOffer: SessionDescription? = null,
    ): Result<String> = runCatching {
        val baseUrl = url.toHttpUrl().parseHttpUrl()

        // An empty last segment means the path ends in '/', which marks it as a directory.
        val isDirectory = baseUrl.pathSegments.last().isEmpty()
        val segments = baseUrl.encodedPathSegments.filter { it.isNotEmpty() }.toMutableList()
        val lastSegment = segments.lastOrNull()
        if (!isDirectory && (lastSegment == "rtc" || lastSegment == "validate")) {
            segments.removeLastOrNull()
        }
        segments.add("rtc")
        segments.add("v1")

        val rtcUrl = baseUrl.newBuilder()
            .encodedPath("/" + segments.joinToString("/"))
            .query(null)
            .fragment(null)
            .build()

        val joinRequest = buildWrappedJoinRequest(
            connectOptions = connectOptions,
            roomOptions = roomOptions,
            networkType = networkType,
            publisherOffer = publisherOffer,
        )

        return@runCatching "${rtcUrl.toString().toWebsocketUrl()}?$QUERY_JOIN_REQUEST=$joinRequest"
    }

    /**
     * Converts a signal websocket URL to its HTTP validation counterpart:
     * - `wss://host/rtc?...` → `https://host/rtc/validate?...`
     * - `wss://host/rtc/v1?...` → `https://host/rtc/v1/validate?...`
     *
     * @return the validation URL, or a failure if [wsUrl] is null or cannot be parsed.
     */
    fun toValidateUrl(wsUrl: String?): Result<String> {
        if (wsUrl == null) {
            return Result.failure(IllegalArgumentException("No signal URL to validate"))
        }
        return runCatching {
            // addPathSegment replaces an empty last segment, so a trailing '/' doesn't double up.
            wsUrl.toHttpUrl().parseHttpUrl()
                .newBuilder()
                .addPathSegment("validate")
                .build()
                .toString()
        }
    }

    /**
     * @return the serialized [WrappedJoinRequest], base64url-encoded with padding.
     */
    private fun buildWrappedJoinRequest(
        connectOptions: ConnectOptions,
        roomOptions: RoomOptions,
        networkType: NetworkType?,
        publisherOffer: SessionDescription?,
    ): String {
        val joinRequest = with(JoinRequest.newBuilder()) {
            clientInfo = with(getClientInfo(connectOptions.clientProtocol).toBuilder()) {
                protocol = connectOptions.protocolVersion.value
                networkType?.let { network = it.protoName }
                build()
            }
            connectionSettings = with(ConnectionSettings.newBuilder()) {
                autoSubscribe = connectOptions.autoSubscribe
                adaptiveStream = roomOptions.adaptiveStream
                build()
            }
            publisherOffer?.let { this.publisherOffer = it }
            if (connectOptions.reconnect) {
                reconnect = true
                reconnectReason = ReconnectReason.RR_SIGNAL_DISCONNECTED
                connectOptions.participantSid?.let { participantSid = it }
            }
            build()
        }.toByteArray()

        // gzip framing outweighs the savings on a small request, so keep whichever is smaller.
        val gzipped = joinRequest.gzip()
        val (payload, compression) = if (gzipped.size < joinRequest.size) {
            gzipped to Compression.GZIP
        } else {
            joinRequest to Compression.NONE
        }

        return WrappedJoinRequest.newBuilder()
            .setCompression(compression)
            .setJoinRequest(ByteString.copyFrom(payload))
            .build()
            .toByteArray()
            .toByteString()
            .base64Url()
    }

    private fun ByteArray.gzip(): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(this) }
        return output.toByteArray()
    }
}
