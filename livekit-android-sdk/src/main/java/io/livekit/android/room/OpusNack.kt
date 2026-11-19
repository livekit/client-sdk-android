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

package io.livekit.android.room

import android.gov.nist.javax.sdp.fields.AttributeField
import android.javax.sdp.MediaDescription
import android.javax.sdp.SdpFactory
import io.livekit.android.webrtc.getRtps

/**
 * Adds plain NACK feedback to every Opus payload in an audio section.
 *
 * The SFU negotiates audio feedback from the first audio section, so single-PC send-only
 * sections must advertise it even before any subscriptions exist. Wildcard NACK already
 * covers every payload; `nack pli` is separate feedback and does not imply plain NACK.
 */
internal fun ensureOpusNack(mediaDesc: MediaDescription) {
    if (mediaDesc.media.mediaType != "audio") return
    val opusPayloads = mediaDesc.opusPayloads()
    if (opusPayloads.isEmpty()) return
    val nackPayloads = mediaDesc.nackPayloads()
    if ("*" in nackPayloads) return

    for (payload in opusPayloads - nackPayloads) {
        mediaDesc.addAttribute(
            AttributeField().apply {
                name = "rtcp-fb"
                value = "$payload nack"
            },
        )
    }
}

/** Restores offered Opus NACK in the corresponding answer sections, matched by MID. */
internal fun mungeOpusNackForAnswer(answer: String, offer: String, factory: SdpFactory): String {
    if (!offer.contains("a=rtcp-fb:")) return answer
    val nackMids = factory.createSessionDescription(offer).getMediaDescriptions(true)
        .filterIsInstance<MediaDescription>()
        .filter { section ->
            if (section.media.mediaType != "audio") return@filter false
            val opus = section.opusPayloads()
            val feedback = section.nackPayloads()
            opus.isNotEmpty() && ("*" in feedback || opus.any { it in feedback })
        }
        .mapNotNull { it.getAttribute("mid") }
        .toSet()
    if (nackMids.isEmpty()) return answer
    val document = factory.createSessionDescription(answer)
    var changed = false
    for (section in document.getMediaDescriptions(true).filterIsInstance<MediaDescription>()) {
        if (section.getAttribute("mid") !in nackMids) continue
        val before = section.toString()
        ensureOpusNack(section)
        changed = changed || section.toString() != before
    }
    return if (changed) document.toString() else answer
}

/** Returns all Opus payload types in this media section. */
private fun MediaDescription.opusPayloads(): Set<String> = getRtps()
    .map { it.second }
    .filter { it.codec.equals("opus", ignoreCase = true) }
    .map { it.payload.toString() }
    .toSet()

/** Returns payload types with plain NACK feedback, including wildcard feedback. */
private fun MediaDescription.nackPayloads(): Set<String> = getAttributes(true)
    .filterIsInstance<AttributeField>()
    .filter { it.attribute.name == "rtcp-fb" }
    .mapNotNull { attribute ->
        val fields = attribute.value?.trim()?.split(rtcpFeedbackWhitespace) ?: return@mapNotNull null
        if (fields.size == 2 && fields[1] == "nack") fields[0] else null
    }
    .toSet()

private val rtcpFeedbackWhitespace = "\\s+".toRegex()
