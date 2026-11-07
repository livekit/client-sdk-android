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

import livekit.org.webrtc.CandidatePairChangeEvent
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.PeerConnection

/**
 * Which peer connection a candidate pair belongs to. The publisher (uplink) and subscriber (downlink)
 * connections run independent ICE sessions and may select different network paths.
 */
enum class IceTarget {
    /** The publisher (uplink) peer connection, carrying locally published tracks. */
    PUBLISHER,

    /** The subscriber (downlink) peer connection, carrying remotely published tracks. */
    SUBSCRIBER,
}

private fun Any?.orDash() = this?.toString() ?: "-"

/**
 * A single ICE candidate, with its `a=candidate:` line parsed out into fields.
 *
 * Line format (RFC 5245 §15.1):
 * `candidate:<foundation> <component> <transport> <priority> <address> <port> typ <type>
 * [raddr <ip> rport <port>] *(<name> <value>)`
 */
data class IceCandidateInfo(
    /** `host`, `srflx`, `prflx` or `relay`. */
    val type: String?,
    /** `udp` or `tcp`. */
    val protocol: String?,
    /** IP address of the candidate. */
    val address: String?,
    /** Port of the candidate. */
    val port: Int?,
    /** RFC 5245 foundation. Candidates gathered from the same origin with the same transport protocol share one. */
    val foundation: String?,
    /** 1 for RTP, 2 for RTCP. Always 1 when rtcp-mux is in use. */
    val component: Int?,
    /** RFC 5245 priority. Decomposed into [typePreference], [localPreference] and [componentPreference]. */
    val priority: Long?,
    /** The address behind the NAT. Present for `srflx`/`prflx`/`relay` candidates. */
    val relatedAddress: String?,
    /** Port of [relatedAddress]. */
    val relatedPort: Int?,
    /** `active`, `passive` or `so` for TCP candidates. */
    val tcpType: String?,
    /** Any remaining key/value pairs on the line, e.g. `generation`, `ufrag`, `network-id`, `network-cost`. */
    val attributes: Map<String, String>,
    /** Media id (`a=mid`) the candidate is associated with. Null when unset by the remote. */
    val sdpMid: String?,
    /** Index of the m-line in the SDP the candidate is associated with. */
    val sdpMLineIndex: Int,
    /** The STUN/TURN server that produced this candidate, if any. */
    val serverUrl: String?,
    /** Local network interface the candidate was gathered on. Always UNKNOWN for remote candidates. */
    val adapterType: PeerConnection.AdapterType,
    /** The original SDP line. */
    val raw: String,
) {
    /**
     * RFC 5245 type preference, bits 24-31 of the priority. libwebrtc uses host 126 (90 for TCP),
     * prflx 110 (80 for TCP), srflx 100 and relay 0-2 depending on the TURN transport.
     */
    val typePreference: Long?
        get() = priority?.let { (it ushr 24) and 0xFFL }

    /** RFC 5245 local preference, bits 8-23 of the priority. */
    val localPreference: Long?
        get() = priority?.let { (it ushr 8) and 0xFFFFL }

    /** RFC 5245 component preference, bits 0-7 of the priority. */
    val componentPreference: Long?
        get() = priority?.let { it and 0xFFL }

    private fun componentDescription() = when (component) {
        1 -> "1 (RTP)"
        2 -> "2 (RTCP)"
        else -> component?.toString() ?: "-"
    }

    /** Multi-line dump of every field, for logging. */
    fun describe(indent: String = "  ") = buildString {
        appendLine("${indent}type:       ${type.orDash()}  protocol: ${protocol.orDash()}  tcpType: ${tcpType.orDash()}")
        appendLine("${indent}address:    ${address.orDash()}:${port.orDash()}")
        appendLine("${indent}related:    ${relatedAddress?.let { "$it:${relatedPort.orDash()}" }.orDash()}")
        appendLine("${indent}foundation: ${foundation.orDash()}  component: ${componentDescription()}")
        appendLine(
            "${indent}priority:   ${priority.orDash()} " +
                "(typePref=${typePreference.orDash()} localPref=${localPreference.orDash()} componentPref=${componentPreference.orDash()})",
        )
        appendLine("${indent}adapter:    $adapterType  serverUrl: ${serverUrl.orDash()}")
        appendLine("${indent}mid:        ${sdpMid.orDash()} (mLineIndex=$sdpMLineIndex)")
        appendLine("${indent}extra:      ${attributes.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "-" }}")
        append("${indent}sdp:        $raw")
    }

    override fun toString() = "${type ?: "?"}/${protocol ?: "?"} ${address ?: "?"}:${port ?: "?"}" +
        if (adapterType != PeerConnection.AdapterType.UNKNOWN) " via $adapterType" else ""
}

/**
 * Emitted whenever libwebrtc's ICE agent switches the candidate pair that carries media.
 *
 * @see io.livekit.android.events.RoomEvent.SelectedIceCandidatePairChanged
 */
data class SelectedIceCandidatePair(
    /** Which peer connection this pair belongs to. */
    val target: IceTarget,
    /** The locally gathered candidate of the pair. */
    val local: IceCandidateInfo,
    /** The remote peer's candidate of the pair. */
    val remote: IceCandidateInfo,
    /** libwebrtc's description of why the pair changed. Free-form, wording varies between versions. */
    val reason: String?,
    /** Monotonic timestamp (ms) of the last data received on the new pair. Only meaningful across events. */
    val lastDataReceivedMs: Int,
    /** ICE's estimate of how long media was interrupted. 0 the first time a pair is selected. */
    val estimatedDisconnectedTimeMs: Int,
) {
    /** True when media is going through a TURN server on either side. */
    val isRelayed: Boolean
        get() = local.type == "relay" || remote.type == "relay"

    /** Single line, for high frequency logging. */
    fun summary() = "[$target] $local -> $remote relayed=$isRelayed " +
        "gap=${estimatedDisconnectedTimeMs}ms reason=${reason ?: "-"}"

    /** Full dump of the change event and both candidates. */
    fun describe() = buildString {
        appendLine("ICE selected candidate pair changed [$target]")
        appendLine("  relayed:                     $isRelayed")
        appendLine("  reason:                      ${reason ?: "-"}")
        appendLine("  lastDataReceivedMs:          $lastDataReceivedMs")
        appendLine("  estimatedDisconnectedTimeMs: $estimatedDisconnectedTimeMs")
        appendLine("  local:")
        appendLine(local.describe("    "))
        appendLine("  remote:")
        append(remote.describe("    "))
    }
}

private const val CANDIDATE_ATTRIBUTE_START_INDEX = 6
private val PARSED_CANDIDATE_ATTRIBUTES = setOf("typ", "raddr", "rport", "tcptype")

/**
 * Parses the `a=candidate:` line of an [IceCandidate]. Unparseable fields come back as null rather than
 * throwing, so that logging never breaks on an unexpected candidate line.
 */
internal fun IceCandidate.toIceCandidateInfo(): IceCandidateInfo {
    val tokens = sdp.trim().removePrefix("a=").split(' ').filter { it.isNotEmpty() }

    // Everything past the fixed fields is a sequence of name/value pairs, including `typ`, `raddr`,
    // `rport` and `tcptype`.
    val attributes = mutableMapOf<String, String>()
    var index = CANDIDATE_ATTRIBUTE_START_INDEX
    while (index + 1 < tokens.size) {
        attributes[tokens[index]] = tokens[index + 1]
        index += 2
    }

    return IceCandidateInfo(
        type = attributes["typ"],
        protocol = tokens.getOrNull(2),
        address = tokens.getOrNull(4),
        port = tokens.getOrNull(5)?.toIntOrNull(),
        foundation = tokens.getOrNull(0)?.removePrefix("candidate:"),
        component = tokens.getOrNull(1)?.toIntOrNull(),
        priority = tokens.getOrNull(3)?.toLongOrNull(),
        relatedAddress = attributes["raddr"],
        relatedPort = attributes["rport"]?.toIntOrNull(),
        tcpType = attributes["tcptype"],
        attributes = attributes.filterKeys { it !in PARSED_CANDIDATE_ATTRIBUTES },
        sdpMid = sdpMid,
        sdpMLineIndex = sdpMLineIndex,
        serverUrl = serverUrl?.takeIf { it.isNotEmpty() },
        adapterType = adapterType ?: PeerConnection.AdapterType.UNKNOWN,
        raw = sdp,
    )
}

internal fun CandidatePairChangeEvent.toSelectedIceCandidatePair(target: IceTarget) = SelectedIceCandidatePair(
    target = target,
    local = local.toIceCandidateInfo(),
    remote = remote.toIceCandidateInfo(),
    reason = reason,
    lastDataReceivedMs = lastDataReceivedMs,
    estimatedDisconnectedTimeMs = estimatedDisconnectedTimeMs,
)
