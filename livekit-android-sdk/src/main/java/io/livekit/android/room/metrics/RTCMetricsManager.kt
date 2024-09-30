/*
 * Copyright 2024 LiveKit, Inc.
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

package io.livekit.android.room.metrics

import io.livekit.android.room.RTCEngine
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.LKLog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import livekit.LivekitMetrics.MetricLabel
import livekit.LivekitMetrics.MetricSample
import livekit.LivekitMetrics.MetricsBatch
import livekit.LivekitMetrics.TimeSeriesMetric
import livekit.LivekitModels.DataPacket
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsReport
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Handles getting the WebRTC metrics and sending them through the data channels.
 *
 * See [RTCMetric] for the related metrics we send.
 */
internal suspend fun collectMetrics(room: Room, rtcEngine: RTCEngine) = coroutineScope {
    launch { collectPublisherMetrics(room, rtcEngine) }
    launch { collectSubscriberMetrics(room, rtcEngine) }
}

private suspend fun collectPublisherMetrics(room: Room, rtcEngine: RTCEngine) {
    while (currentCoroutineContext().isActive) {
        delay(1000)
        val report = suspendCancellableCoroutine { cont ->
            room.getPublisherRTCStats { cont.resume(it) }
        }

        val strings = mutableListOf<String>()
        val stats = findPublisherVideoStats(strings, room, report, room.localParticipant.identity)

        val dataPacket = with(DataPacket.newBuilder()) {
            metrics = with(MetricsBatch.newBuilder()) {
                timestampMs = report.timestampUs.microToMilli()
                addAllStrData(strings)
                addAllTimeSeries(stats)
                build()
            }
            kind = DataPacket.Kind.RELIABLE
            build()
        }

        try {
            rtcEngine.sendData(dataPacket)
        } catch (e: Exception) {
            LKLog.i(e) { "Error sending metrics: " }
        }
    }
}

private suspend fun collectSubscriberMetrics(room: Room, rtcEngine: RTCEngine) {
    while (currentCoroutineContext().isActive) {
        delay(1000)
        val report = suspendCancellableCoroutine { cont ->
            room.getSubscriberRTCStats { cont.resume(it) }
        }

        val strings = mutableListOf<String>()
        val stats = findSubscriberAudioStats(strings, report, room.localParticipant.identity) +
            findSubscriberVideoStats(strings, report, room.localParticipant.identity)

        val dataPacket = with(DataPacket.newBuilder()) {
            metrics = with(MetricsBatch.newBuilder()) {
                timestampMs = report.timestampUs.microToMilli()
                addAllStrData(strings)
                addAllTimeSeries(stats)
                build()
            }
            kind = DataPacket.Kind.RELIABLE
            build()
        }

        try {
            rtcEngine.sendData(dataPacket)
        } catch (e: Exception) {
            LKLog.i(e) { "Error sending metrics: " }
        }
    }
}

private fun findPublisherVideoStats(strings: MutableList<String>, room: Room, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
    val mediaSources = report.statsMap
        .values
        .filter { stat -> stat.type == "media-source" && stat.members["kind"] == "video" }
    val videoTracks = report.statsMap
        .values
        .filter { stat -> stat.type == "outbound-rtp" && stat.members["kind"] == "video" }
        .mapNotNull { stat -> stat to getPublishVideoTrackSid(room, mediaSources, stat) }

    val metrics = videoTracks
        .flatMap { (stat, trackSid) ->
            val durations = stat.members["qualityLimitationDurations"] as? Map<*, *> ?: return emptyList()
            val rid = stat.members["rid"] as? String
            qualityLimitations.mapNotNull { (label, key) ->
                val duration = durations[key] as? Number ?: return@mapNotNull null
                val sample = createMetricSample(stat.timestampUs.microToMilli(), duration)
                createTimeSeries(
                    label = label.protoLabel,
                    strings = strings,
                    samples = listOf(sample),
                    identity = participantIdentity,
                    trackSid = trackSid,
                    rid = rid,
                )
            }
        }

    return metrics
}

/**
 * The track sid isn't available on outbound-rtp stats, so we cross-reference against
 * the MediaSource trackIdentifier (which is a locally generated id), and then look up
 * the local published track for the sid.
 */
private fun getPublishVideoTrackSid(room: Room, mediaSources: List<RTCStats>, videoTrack: RTCStats): String? {
    val mediaSourceId = videoTrack.members["mediaSourceId"] ?: return null
    val mediaSource = mediaSources.firstOrNull { m -> m.id == mediaSourceId } ?: return null
    val trackIdentifier = mediaSource.members["trackIdentifier"] ?: return null

    val trackPubPair = room.localParticipant.videoTrackPublications
        .firstOrNull { (_, track) -> track?.rtcTrack?.id() == trackIdentifier } ?: return null

    val (publication) = trackPubPair

    return publication.sid
}

private fun findSubscriberAudioStats(strings: MutableList<String>, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
    val audioTracks = report.statsMap.filterValues { stat ->
        stat.type == "inbound-rtp" && stat.members["kind"] == "audio"
    }

    val metrics = audioTracks.values
        .flatMap { stat ->
            listOf(
                RTCMetric.CONCEALED_SAMPLES,
                RTCMetric.CONCEALMENT_EVENTS,
                RTCMetric.SILENT_CONCEALED_SAMPLES,
                RTCMetric.JITTER_BUFFER_DELAY,
                RTCMetric.JITTER_BUFFER_EMITTED_COUNT,
            ).mapNotNull { metric ->
                createTimeSeriesForMetric(
                    stat = stat,
                    metric = metric,
                    strings = strings,
                    identity = participantIdentity,
                )
            }
        }

    return metrics
}

private fun findSubscriberVideoStats(strings: MutableList<String>, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
    val videoTracks = report.statsMap.filterValues { stat ->
        stat.type == "inbound-rtp" && stat.members["kind"] == "video"
    }

    val metrics = videoTracks.values
        .flatMap { stat ->
            listOf(
                RTCMetric.FREEZE_COUNT,
                RTCMetric.TOTAL_FREEZES_DURATION,
                RTCMetric.PAUSE_COUNT,
                RTCMetric.TOTAL_PAUSES_DURATION,
                RTCMetric.JITTER_BUFFER_DELAY,
                RTCMetric.JITTER_BUFFER_EMITTED_COUNT,
            ).mapNotNull { metric ->
                createTimeSeriesForMetric(
                    stat = stat,
                    metric = metric,
                    strings = strings,
                    identity = participantIdentity,
                )
            }
        }

    return metrics
}

// Utility methods

/**
 * Gets the final index to use for indexes pointing at the MetricsBatch.str_data.
 * Index starts at [MetricLabel.METRIC_LABEL_PREDEFINED_MAX_VALUE].
 *
 * Receivers should parse index values like so:
 * ```
 * if index < LABEL_MAX_VALUE
 *    MetricLabel[index]
 * else
 *    str_data[index - 4096]
 * ```
 */
private fun MutableList<String>.getOrCreateIndex(string: String): Int {
    var index = indexOf(string)

    if (index == -1) {
        // Doesn't exist, create.
        add(string)
        index = size - 1
    }

    return index + MetricLabel.METRIC_LABEL_PREDEFINED_MAX_VALUE.number
}

private fun createMetricSample(
    timestampMs: Long,
    value: Number,
): MetricSample {
    return with(MetricSample.newBuilder()) {
        this.timestampMs = timestampMs
        this.value = value.toFloat()
        build()
    }
}

private fun createTimeSeriesForMetric(
    stat: RTCStats,
    metric: RTCMetric,
    strings: MutableList<String>,
    identity: Participant.Identity? = null,
): TimeSeriesMetric? {
    val value = stat.members[metric.statKey] as? Number ?: return null
    val trackSid = stat.members["trackIdentifier"] as? String ?: return null
    val rid = stat.members["rid"] as? String

    val sample = createMetricSample(stat.timestampUs.microToMilli(), value)

    return createTimeSeries(
        label = metric.protoLabel,
        strings = strings,
        samples = listOf(sample),
        identity = identity,
        trackSid = trackSid,
        rid = rid,
    )
}

private fun createTimeSeries(
    label: MetricLabel,
    strings: MutableList<String>,
    samples: List<MetricSample>,
    identity: Participant.Identity? = null,
    trackSid: String? = null,
    rid: String? = null,
): TimeSeriesMetric {
    return with(TimeSeriesMetric.newBuilder()) {
        this.label = label.number

        if (identity != null) {
            this.participantIdentity = strings.getOrCreateIndex(identity.value)
        }
        if (trackSid != null) {
            this.trackSid = strings.getOrCreateIndex(trackSid)
        }

        if (rid != null) {
            this.rid = strings.getOrCreateIndex(rid)
        }
        this.addAllSamples(samples)
        build()
    }
}

private fun Number.microToMilli(): Long {
    return TimeUnit.MILLISECONDS.convert(this.toLong(), TimeUnit.MILLISECONDS)
}

private enum class RTCMetric(val protoLabel: MetricLabel, val statKey: String) {
    FREEZE_COUNT(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_FREEZE_COUNT, "freezeCount"),
    TOTAL_FREEZES_DURATION(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_TOTAL_FREEZE_DURATION, "totalFreezesDuration"),
    PAUSE_COUNT(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_PAUSE_COUNT, "pauseCount"),
    TOTAL_PAUSES_DURATION(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_TOTAL_PAUSES_DURATION, "totalPausesDuration"),

    CONCEALED_SAMPLES(MetricLabel.CLIENT_AUDIO_SUBSCRIBER_CONCEALED_SAMPLES, "concealedSamples"),
    SILENT_CONCEALED_SAMPLES(MetricLabel.CLIENT_AUDIO_SUBSCRIBER_SILENT_CONCEALED_SAMPLES, "silentConcealedSamples"),
    CONCEALMENT_EVENTS(MetricLabel.CLIENT_AUDIO_SUBSCRIBER_CONCEALMENT_EVENTS, "concealmentEvents"),

    JITTER_BUFFER_DELAY(MetricLabel.CLIENT_SUBSCRIBER_JITTER_BUFFER_DELAY, "jitterBufferDelay"),
    JITTER_BUFFER_EMITTED_COUNT(MetricLabel.CLIENT_SUBSCRIBER_JITTER_BUFFER_EMITTED_COUNT, "jitterBufferEmittedCount"),

    QUALITY_LIMITATION_DURATION_BANDWIDTH(MetricLabel.CLIENT_VIDEO_PUBLISHER_QUALITY_LIMITATION_DURATION_BANDWIDTH, "qualityLimitationDurations"),
    QUALITY_LIMITATION_DURATION_CPU(MetricLabel.CLIENT_VIDEO_PUBLISHER_QUALITY_LIMITATION_DURATION_CPU, "qualityLimitationDurations"),
    QUALITY_LIMITATION_DURATION_OTHER(MetricLabel.CLIENT_VIDEO_PUBLISHER_QUALITY_LIMITATION_DURATION_OTHER, "qualityLimitationDurations"),
}

private val qualityLimitations = listOf(
    RTCMetric.QUALITY_LIMITATION_DURATION_CPU to "cpu",
    RTCMetric.QUALITY_LIMITATION_DURATION_BANDWIDTH to "bandwidth",
    RTCMetric.QUALITY_LIMITATION_DURATION_OTHER to "other",
)
