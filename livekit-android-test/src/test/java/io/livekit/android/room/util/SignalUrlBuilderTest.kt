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

import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.stats.NetworkType
import io.livekit.android.stats.getClientInfo
import livekit.LivekitModels
import livekit.LivekitRtc
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.GZIPInputStream

class SignalUrlBuilderTest {

    private fun buildUrl(
        url: String = URL,
        connectOptions: ConnectOptions = ConnectOptions(),
        roomOptions: RoomOptions = RoomOptions(adaptiveStream = true),
        networkType: NetworkType? = null,
        publisherOffer: LivekitRtc.SessionDescription? = null,
    ): String = SignalUrlBuilder.buildJoinRequestUrl(
        url = url,
        connectOptions = connectOptions,
        roomOptions = roomOptions,
        networkType = networkType,
        publisherOffer = publisherOffer,
    ).getOrThrow()

    /** Options as `SignalClient.reconnect` sets them. */
    private fun reconnectOptions(participantSid: String?) = ConnectOptions().apply {
        reconnect = true
        this.participantSid = participantSid
    }

    /** OkHttp's [HttpUrl] only parses http(s), so ws(s) URLs are parsed with the equivalent scheme. */
    private fun parseWs(wsUrl: String): HttpUrl {
        assertTrue("expected ws(s) scheme: $wsUrl", wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://"))
        return wsUrl.replaceFirst("ws", "http").toHttpUrl()
    }

    /** The percent-encoded query is what actually goes on the wire. */
    private fun joinRequestParam(url: String): String {
        val query = parseWs(url).encodedQuery!!
        return query.split("&")
            .first { it.startsWith("${SignalUrlBuilder.QUERY_JOIN_REQUEST}=") }
            .removePrefix("${SignalUrlBuilder.QUERY_JOIN_REQUEST}=")
    }

    private fun decodeWrapped(url: String): LivekitRtc.WrappedJoinRequest {
        val bytes = Base64.getUrlDecoder().decode(joinRequestParam(url))
        return LivekitRtc.WrappedJoinRequest.parseFrom(bytes)
    }

    private fun decodeJoinRequest(url: String): LivekitRtc.JoinRequest {
        val wrapped = decodeWrapped(url)
        val joinRequestBytes = when (wrapped.compression) {
            LivekitRtc.WrappedJoinRequest.Compression.GZIP ->
                GZIPInputStream(wrapped.joinRequest.newInput()).use { it.readBytes() }

            else -> wrapped.joinRequest.toByteArray()
        }
        return LivekitRtc.JoinRequest.parseFrom(joinRequestBytes)
    }

    // region join_request encoding

    /**
     * Standard base64 '+' and '/' are not safe in a query value: a receiver parsing the
     * query as form-urlencoded turns '+' into a space.
     */
    @Test
    fun joinRequestParamIsUrlSafe() {
        val param = joinRequestParam(buildUrl(publisherOffer = largeOffer()))

        assertFalse("Standard base64 leaked into the query: $param", param.contains("+"))
        assertFalse("Standard base64 leaked into the query: $param", param.contains("/"))
    }

    /** Padding is kept, matching client-sdk-js, rust-sdks and client-sdk-swift. */
    @Test
    fun joinRequestParamKeepsPadding() {
        val param = joinRequestParam(buildUrl())

        assertEquals("Unpadded base64: $param", 0, param.length % 4)
    }

    @Test
    fun joinRequestRoundTrips() {
        val request = decodeJoinRequest(buildUrl())

        assertEquals(LivekitModels.ClientInfo.SDK.ANDROID, request.clientInfo.sdk)
        assertEquals(getClientInfo().version, request.clientInfo.version)
        assertTrue(request.connectionSettings.adaptiveStream)
        assertFalse(request.reconnect)
        assertEquals("", request.participantSid)
    }

    @Test
    fun reconnectJoinRequestRoundTrips() {
        val request = decodeJoinRequest(buildUrl(connectOptions = reconnectOptions(participantSid = "PA_123")))

        assertTrue(request.reconnect)
        assertEquals(LivekitModels.ReconnectReason.RR_SIGNAL_DISCONNECTED, request.reconnectReason)
        assertEquals("PA_123", request.participantSid)
    }

    @Test
    fun participantSidIsOnlySentOnReconnect() {
        val options = ConnectOptions().apply { participantSid = "PA_123" }
        val request = decodeJoinRequest(buildUrl(connectOptions = options))

        assertFalse(request.reconnect)
        assertEquals("", request.participantSid)
    }

    @Test
    fun joinRequestCarriesClientInfo() {
        val options = ConnectOptions()
        val request = decodeJoinRequest(buildUrl())

        assertEquals(options.protocolVersion.value, request.clientInfo.protocol)
        assertEquals(options.clientProtocol.value, request.clientInfo.clientProtocol)
        assertEquals(getClientInfo().os, request.clientInfo.os)
    }

    @Test
    fun joinRequestCarriesConnectionSettings() {
        val request = decodeJoinRequest(
            buildUrl(
                connectOptions = ConnectOptions(autoSubscribe = false),
                roomOptions = RoomOptions(adaptiveStream = false),
            ),
        )

        assertFalse(request.connectionSettings.autoSubscribe)
        assertFalse(request.connectionSettings.adaptiveStream)
    }

    @Test
    fun joinRequestCarriesNetworkType() {
        val request = decodeJoinRequest(buildUrl(networkType = NetworkType.CELLULAR))

        assertEquals("cellular", request.clientInfo.network)
    }

    @Test
    fun networkTypeIsOmittedWhenUnknown() {
        val request = decodeJoinRequest(buildUrl(networkType = null))

        assertEquals("", request.clientInfo.network)
    }

    @Test
    fun joinRequestBundlesPublisherOffer() {
        val offer = LivekitRtc.SessionDescription.newBuilder()
            .setType("offer")
            .setSdp("v=0\r\n")
            .setId(7)
            .build()
        val request = decodeJoinRequest(buildUrl(publisherOffer = offer))

        assertEquals(offer, request.publisherOffer)
    }

    @Test
    fun joinRequestHasNoPublisherOfferByDefault() {
        val request = decodeJoinRequest(buildUrl())

        assertFalse(request.hasPublisherOffer())
    }

    /**
     * A minimal join request is small enough that gzip framing outweighs the
     * savings, so it must stay uncompressed rather than growing the URL.
     */
    @Test
    fun smallRequestIsNotCompressed() {
        val wrapped = decodeWrapped(buildUrl())

        assertEquals(LivekitRtc.WrappedJoinRequest.Compression.NONE, wrapped.compression)
    }

    @Test
    fun largeRequestIsCompressed() {
        val offer = largeOffer()
        val wrapped = decodeWrapped(buildUrl(publisherOffer = offer))

        assertEquals(LivekitRtc.WrappedJoinRequest.Compression.GZIP, wrapped.compression)
        assertTrue(wrapped.joinRequest.size() < offer.serializedSize)
        assertEquals(offer, decodeJoinRequest(buildUrl(publisherOffer = offer)).publisherOffer)
    }

    // endregion

    // region URL

    @Test
    fun onlyJoinRequestParam() {
        val url = buildUrl(connectOptions = reconnectOptions(participantSid = "PA_123"), networkType = NetworkType.WIFI)

        assertEquals(setOf(SignalUrlBuilder.QUERY_JOIN_REQUEST), parseWs(url).queryParameterNames)
    }

    @Test
    fun httpsBecomesWss() {
        val url = buildUrl(url = "https://example.livekit.cloud")

        assertTrue(url, url.startsWith("wss://example.livekit.cloud/rtc/v1?"))
    }

    @Test
    fun httpBecomesWs() {
        val url = buildUrl(url = "http://localhost:7880")

        assertTrue(url, url.startsWith("ws://localhost:7880/rtc/v1?"))
    }

    @Test
    fun wssIsKept() {
        val url = buildUrl(url = "wss://example.livekit.cloud")

        assertTrue(url, url.startsWith("wss://example.livekit.cloud/rtc/v1?"))
    }

    @Test
    fun trailingSlashIsHandled() {
        assertEquals("/rtc/v1", parseWs(buildUrl(url = "wss://example.livekit.cloud/")).encodedPath)
    }

    @Test
    fun pathPrefixIsKept() {
        assertEquals("/livekit/rtc/v1", parseWs(buildUrl(url = "wss://example.com/livekit")).encodedPath)
        assertEquals("/livekit/rtc/v1", parseWs(buildUrl(url = "wss://example.com/livekit/")).encodedPath)
    }

    @Test
    fun trailingRtcSegmentIsReplaced() {
        assertEquals("/rtc/v1", parseWs(buildUrl(url = "wss://example.livekit.cloud/rtc")).encodedPath)
        assertEquals("/livekit/rtc/v1", parseWs(buildUrl(url = "wss://example.com/livekit/rtc")).encodedPath)
    }

    @Test
    fun trailingValidateSegmentIsReplaced() {
        assertEquals("/rtc/v1", parseWs(buildUrl(url = "wss://example.livekit.cloud/validate")).encodedPath)
    }

    @Test
    fun hostNamedLikeSignalSegmentIsKept() {
        val url = buildUrl(url = "ws://rtc:7880")

        assertTrue(url, url.startsWith("ws://rtc:7880/rtc/v1?"))
    }

    @Test
    fun baseUrlQueryIsDropped() {
        val url = buildUrl(url = "wss://example.livekit.cloud/livekit?foo=bar")

        assertEquals("/livekit/rtc/v1", parseWs(url).encodedPath)
        assertEquals(setOf(SignalUrlBuilder.QUERY_JOIN_REQUEST), parseWs(url).queryParameterNames)
    }

    @Test
    fun invalidUrlFails() {
        val result = SignalUrlBuilder.buildJoinRequestUrl(
            url = "not a url",
            connectOptions = ConnectOptions(),
            roomOptions = RoomOptions(),
        )

        assertTrue(result.isFailure)
    }

    // endregion

    // region toValidateUrl

    @Test
    fun validateUrlForLegacyPath() {
        val validate = SignalUrlBuilder.toValidateUrl("wss://example.livekit.cloud/rtc?protocol=13&sdk=android").getOrThrow()

        assertEquals("https://example.livekit.cloud/rtc/validate?protocol=13&sdk=android", validate)
    }

    @Test
    fun validateUrlForV1Path() {
        val url = buildUrl()
        val validate = SignalUrlBuilder.toValidateUrl(url).getOrThrow().toHttpUrl()

        assertEquals("https", validate.scheme)
        assertEquals("/rtc/v1/validate", validate.encodedPath)
        assertEquals(joinRequestParam(url), validate.encodedQuery!!.removePrefix("${SignalUrlBuilder.QUERY_JOIN_REQUEST}="))
    }

    @Test
    fun validateUrlWsBecomesHttp() {
        val validate = SignalUrlBuilder.toValidateUrl("ws://localhost:7880/rtc/v1?join_request=abc=").getOrThrow()

        assertEquals("http://localhost:7880/rtc/v1/validate?join_request=abc=", validate)
    }

    @Test
    fun validateUrlHandlesTrailingSlash() {
        val validate = SignalUrlBuilder.toValidateUrl("wss://example.livekit.cloud/rtc/").getOrThrow()

        assertEquals("https://example.livekit.cloud/rtc/validate", validate)
    }

    @Test
    fun validateUrlInvalidUrlFails() {
        assertTrue(SignalUrlBuilder.toValidateUrl("not a url").isFailure)
    }

    @Test
    fun validateUrlNullUrlFails() {
        assertTrue(SignalUrlBuilder.toValidateUrl(null).isFailure)
    }

    // endregion

    companion object {
        private const val URL = "wss://example.livekit.cloud"

        /** An SDP-sized offer: large enough to compress well and to need the full base64 alphabet. */
        private fun largeOffer(): LivekitRtc.SessionDescription {
            val sdp = (0 until 40).joinToString("\r\n") { i ->
                "a=candidate:$i 1 udp ${2122260223 - i} 192.168.1.${i % 255} ${50000 + i} typ host generation 0 ufrag ~?>+/"
            }
            return LivekitRtc.SessionDescription.newBuilder()
                .setType("offer")
                .setSdp(sdp)
                .setId(1)
                .build()
        }
    }
}
