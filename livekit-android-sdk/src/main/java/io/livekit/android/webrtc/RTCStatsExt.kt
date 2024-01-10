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

import io.livekit.android.util.LKLog
import kotlinx.coroutines.suspendCancellableCoroutine
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsCollectorCallback
import livekit.org.webrtc.RTCStatsReport
import kotlin.coroutines.resume

/**
 * Returns an RTCStatsReport with all the relevant information pertaining to a track.
 *
 * @param trackIdentifier track, sender, or receiver id
 */
fun RTCStatsReport.getFilteredStats(track: MediaStreamTrack): RTCStatsReport {
    return getFilteredStats(track.id())
}

/**
 * Returns an RTCStatsReport with all the relevant information pertaining to a track identifier.
 *
 * @param trackIdentifier track, sender, or receiver id
 */
fun RTCStatsReport.getFilteredStats(trackIdentifier: String): RTCStatsReport {
    val rtcStatsReport = this
    val statsMap = rtcStatsReport.statsMap
    val filteredStats = mutableSetOf<RTCStats>()

    // Get track stats
    val trackStats = getTrackStats(trackIdentifier, statsMap)
    if (trackStats == null) {
        LKLog.i { "getStats: couldn't find track stats!" }
        return RTCStatsReport(rtcStatsReport.timestampUs.toLong(), HashMap())
    }
    filteredStats.add(trackStats)
    val trackId = trackStats.id

    // Get stream stats
    val streamStats = getStreamStats(trackId, statsMap)
    if (streamStats != null) {
        filteredStats.add(streamStats)
    }

    // Get streamType stats and associated information
    val ssrcs: MutableSet<Long?> = HashSet()
    val codecIds: MutableSet<String?> = HashSet()

    for (stats in statsMap.values) {
        if ((stats.type == "inbound-rtp" || stats.type == "outbound-rtp") && trackId == stats.members["trackId"]) {
            ssrcs.add(stats.members["ssrc"] as Long?)
            codecIds.add(stats.members["codecId"] as String?)
            filteredStats.add(stats)
        }
    }

    // Get nominated candidate information
    var candidatePairStats: RTCStats? = null
    for (stats in statsMap.values) {
        if (stats.type == "candidate-pair" && stats.members["nominated"] == true) {
            candidatePairStats = stats
            break
        }
    }

    var localCandidateId: String? = null
    var remoteCandidateId: String? = null
    if (candidatePairStats != null) {
        filteredStats.add(candidatePairStats)
        localCandidateId = candidatePairStats.members["localCandidateId"] as String?
        remoteCandidateId = candidatePairStats.members["remoteCandidateId"] as String?
    }

    // Sweep for any remaining stats we want.
    filteredStats.addAll(
        getExtraStats(
            trackIdentifier,
            ssrcs,
            codecIds,
            localCandidateId,
            remoteCandidateId,
            statsMap,
        ),
    )
    val filteredStatsMap: MutableMap<String, RTCStats> = HashMap()
    for (stats in filteredStats) {
        filteredStatsMap[stats.id] = stats
    }

    return RTCStatsReport(rtcStatsReport.timestampUs.toLong(), filteredStatsMap)
}

// Note: trackIdentifier can differ from the internal stats trackId
// trackIdentifier refers to the sender or receiver id
private fun getTrackStats(trackIdentifier: String, statsMap: Map<String, RTCStats>): RTCStats? {
    for (stats in statsMap.values) {
        if (stats.type == "track" && trackIdentifier == stats.members["trackIdentifier"]) {
            return stats
        }
    }
    return null
}

private fun getStreamStats(trackId: String, statsMap: Map<String, RTCStats>): RTCStats? {
    for (stats in statsMap.values) {
        if (stats.type == "stream") {
            val trackIds = (stats.members["trackIds"] as? Array<*>)?.toList() ?: emptyList()
            if (trackIds.contains(trackId)) {
                return stats
            }
        }
    }
    return null
}

// Note: trackIdentifier can differ from the internal stats trackId
// trackIdentifier refers to the sender or receiver id
private fun getExtraStats(
    trackIdentifier: String,
    ssrcs: Set<Long?>,
    codecIds: Set<String?>,
    localCandidateId: String?,
    remoteCandidateId: String?,
    statsMap: Map<String, RTCStats>,
): Set<RTCStats> {
    val extraStats: MutableSet<RTCStats> = HashSet()
    for (stats in statsMap.values) {
        when (stats.type) {
            "certificate", "transport" -> extraStats.add(stats)
        }
        if (stats.id == localCandidateId || stats.id == remoteCandidateId) {
            extraStats.add(stats)
            continue
        }
        if (ssrcs.contains(stats.members["ssrc"])) {
            extraStats.add(stats)
            continue
        }
        if (trackIdentifier == stats.members["trackIdentifier"]) {
            extraStats.add(stats)
            continue
        }
        if (codecIds.contains(stats.id)) {
            extraStats.add(stats)
        }
    }
    return extraStats
}

typealias RTCStatsGetter = (RTCStatsCollectorCallback) -> Unit

suspend fun RTCStatsGetter.getStats(): RTCStatsReport = suspendCancellableCoroutine { cont ->
    val listener = RTCStatsCollectorCallback { report ->
        cont.resume(report)
    }
    this.invoke(listener)
}
