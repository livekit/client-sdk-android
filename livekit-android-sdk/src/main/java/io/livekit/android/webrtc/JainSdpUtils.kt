/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android.webrtc

import android.gov.nist.javax.sdp.fields.AttributeField
import android.javax.sdp.MediaDescription
import io.livekit.android.util.LKLog

/**
 * @suppress
 */
data class SdpRtp(val payload: Long, val codec: String, val rate: Long?, val encoding: String?)

/**
 * @suppress
 */
fun MediaDescription.getRtps(): List<Pair<AttributeField, SdpRtp>> {
    return getAttributes(true)
        .filterIsInstance<AttributeField>()
        .filter { it.attribute.name == "rtpmap" }
        .mapNotNull {
            val rtp = tryParseRtp(it.value)
            if (rtp == null) {
                LKLog.w { "could not parse rtpmap: ${it.encode()}" }
                return@mapNotNull null
            }
            it to rtp
        }
}

private val RTP = """(\d*) ([\w\-.]*)(?:\s*/(\d*)(?:\s*/(\S*))?)?""".toRegex()
internal fun tryParseRtp(string: String): SdpRtp? {
    val match = RTP.matchEntire(string) ?: return null
    val (payload, codec, rate, encoding) = match.destructured
    return SdpRtp(payload.toLong(), codec, toOptionalLong(rate), toOptionalString(encoding))
}

/**
 * @suppress
 */
data class SdpMsid(
    /** holds the msid-id (and msid-appdata if available) */
    val value: String,
)

/**
 * @suppress
 */
fun MediaDescription.getMsid(): SdpMsid? {
    val attribute = getAttribute("msid") ?: return null
    return SdpMsid(attribute)
}

/**
 * @suppress
 */
data class SdpFmtp(val payload: Long, val config: String) {
    fun toAttributeField(): AttributeField {
        return AttributeField().apply {
            name = "fmtp"
            value = "$payload $config"
        }
    }
}

/**
 * @suppress
 */
fun MediaDescription.getFmtps(): List<Pair<AttributeField, SdpFmtp>> {
    return getAttributes(true)
        .filterIsInstance<AttributeField>()
        .filter { it.attribute.name == "fmtp" }
        .mapNotNull {
            val fmtp = tryParseFmtp(it.value)
            if (fmtp == null) {
                LKLog.w { "could not parse fmtp: ${it.encode()}" }
                return@mapNotNull null
            }
            it to fmtp
        }
}

private val FMTP = """(\d*) ([\S| ]*)""".toRegex()
internal fun tryParseFmtp(string: String): SdpFmtp? {
    val match = FMTP.matchEntire(string) ?: return null
    val (payload, config) = match.destructured
    return SdpFmtp(payload.toLong(), config)
}

/**
 * @suppress
 */
data class SdpExt(val value: Long, val direction: String?, val encryptUri: String?, val uri: String, val config: String?) {
    fun toAttributeField(): AttributeField {
        return AttributeField().apply {
            name = "extmap"
            value = buildString {
                append(this@SdpExt.value)
                if (direction != null) {
                    append(" $direction")
                }
                if (encryptUri != null) {
                    append(" $encryptUri")
                }
                append(" $uri")
                if (config != null) {
                    append(" $config")
                }
            }
        }
    }
}

/**
 * @suppress
 */
fun MediaDescription.getExts(): List<Pair<AttributeField, SdpExt>> {
    return getAttributes(true)
        .filterIsInstance<AttributeField>()
        .filter { it.attribute.name == "extmap" }
        .mapNotNull {
            val ext = tryParseExt(it.value)
            if (ext == null) {
                LKLog.w { "could not parse extmap: ${it.encode()}" }
                return@mapNotNull null
            }
            it to ext
        }
}

private val EXT = """(\d+)(?:/(\w+))?(?: (urn:ietf:params:rtp-hdrext:encrypt))? (\S*)(?: (\S*))?""".toRegex()
internal fun tryParseExt(string: String): SdpExt? {
    val match = EXT.matchEntire(string) ?: return null
    val (value, direction, encryptUri, uri, config) = match.destructured
    return SdpExt(value.toLong(), toOptionalString(direction), toOptionalString(encryptUri), uri, toOptionalString(config))
}

internal fun toOptionalLong(str: String): Long? = if (str.isEmpty()) null else str.toLong()
internal fun toOptionalString(str: String): String? = str.ifEmpty { null }
