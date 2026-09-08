/*
 * Copyright 2023-2026 LiveKit, Inc.
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

import android.javax.sdp.MediaDescription
import android.javax.sdp.SdpException
import android.javax.sdp.SdpFactory
import android.javax.sdp.SdpParseException
import androidx.annotation.VisibleForTesting
import io.livekit.android.util.LKLog
import livekit.org.webrtc.SessionDescription

private const val OPUS_CODEC = "opus"
private const val STEREO_FMTP_PARAM = "stereo=1"
private const val SPROP_STEREO_FMTP_PARAM = "sprop-stereo=1"
private const val MID_ATTRIBUTE = "mid"

/**
 * Adds `stereo=1` to the Opus fmtp line of the answer for every audio media
 * section where [offer] advertised `sprop-stereo=1`.
 *
 * The native Opus decoder downmixes incoming stereo packets to mono unless the
 * local answer negotiates `stereo=1`, so without this munging a stereo track
 * published by another participant is received as mono.
 *
 * Mirrors the behavior of client-sdk-js, which extracts `remoteStereoMids` from
 * the server offer and rewrites the matching fmtp lines when creating the answer.
 *
 * @suppress
 */
@VisibleForTesting
internal fun SessionDescription.ensureStereoOpus(offer: SessionDescription): SessionDescription {
    val sdpFactory = SdpFactory.getInstance()
    val parsedAnswer = parseSessionDescription(sdpFactory, description) ?: return this
    val parsedOffer = parseSessionDescription(sdpFactory, offer.description) ?: return this

    val stereoMids = findStereoMids(parsedOffer)
    if (stereoMids.isEmpty()) {
        return this
    }

    for (mediaDesc in mediaDescriptionsOf(parsedAnswer)) {
        val mid = midOf(mediaDesc) ?: continue
        if (mid !in stereoMids) continue

        val payloadType = findOpusPayloadType(mediaDesc) ?: continue
        ensureStereoFmtpParam(mediaDesc, payloadType)
    }

    return try {
        SessionDescription(type, parsedAnswer.toString())
    } catch (_: SdpException) {
        this
    }
}

private fun parseSessionDescription(
    sdpFactory: SdpFactory,
    description: String,
): android.javax.sdp.SessionDescription? = try {
    sdpFactory.createSessionDescription(description)
} catch (_: SdpParseException) {
    LKLog.w { "stereo munging: could not parse sdp" }
    null
}

private fun mediaDescriptionsOf(parsed: android.javax.sdp.SessionDescription): List<MediaDescription> {
    val raw = try {
        parsed.getMediaDescriptions(true)
    } catch (_: SdpException) {
        return emptyList()
    }
    return raw.filterIsInstance<MediaDescription>()
}

private fun findStereoMids(offer: android.javax.sdp.SessionDescription): List<String> {
    val mids = mutableListOf<String>()
    for (mediaDesc in mediaDescriptionsOf(offer)) {
        if (isPublisherStereo(mediaDesc)) {
            midOf(mediaDesc)?.let { mids.add(it) }
        }
    }
    return mids
}

private fun midOf(mediaDesc: MediaDescription): String? = try {
    mediaDesc.getAttribute(MID_ATTRIBUTE)
} catch (_: SdpParseException) {
    null
}

private fun isPublisherStereo(mediaDesc: MediaDescription): Boolean {
    val payloadType = findOpusPayloadType(mediaDesc) ?: return false
    for ((_, fmtp) in mediaDesc.getFmtps()) {
        if (fmtp.payload == payloadType && fmtp.config.split(";").any { it.trim() == SPROP_STEREO_FMTP_PARAM }) {
            return true
        }
    }
    return false
}

private fun findOpusPayloadType(mediaDesc: MediaDescription): Long? {
    for ((_, rtp) in mediaDesc.getRtps()) {
        if (rtp.codec.equals(OPUS_CODEC, ignoreCase = true)) {
            return rtp.payload
        }
    }
    return null
}

/* The native Opus decoder requires both sides of the negotiation to carry
stereo=1. The server only puts sprop-stereo=1 into its offer; the answer must
add the stereo=1 parameter itself or received packets are decoded as mono.
*/
private fun ensureStereoFmtpParam(mediaDesc: MediaDescription, payloadType: Long) {
    var fmtpFound = false
    for ((attribute, fmtp) in mediaDesc.getFmtps()) {
        if (fmtp.payload != payloadType) {
            continue
        }
        fmtpFound = true
        if (!fmtp.config.split(";").any { it.trim() == STEREO_FMTP_PARAM }) {
            try {
                attribute.setValue("${fmtp.payload} ${fmtp.config};$STEREO_FMTP_PARAM")
            } catch (_: SdpException) {
                LKLog.w { "stereo munging: failed to update opus fmtp line" }
            }
        }
        break
    }

    // Not found, add manually
    if (!fmtpFound) {
        mediaDesc.addAttribute(
            SdpFmtp(payloadType, STEREO_FMTP_PARAM).toAttributeField(),
        )
    }
}
