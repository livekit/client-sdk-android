@file:OptIn(ExperimentalCoroutinesApi::class)

package io.livekit.android.room.metrics

import com.github.ajalt.timberkt.Timber
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.LKLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import livekit.LivekitModels
import livekit.LivekitModels.DataPacket
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsReport
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

suspend fun collectMetricsTracking(room: Room, rtcEngine: RTCEngine) = coroutineScope {
    launch { collectPublisherMetrics(room, rtcEngine) }
    launch { collectSubscriberMetrics(room, rtcEngine) }
}

suspend fun collectPublisherMetrics(room: Room, rtcEngine: RTCEngine) {
    while (currentCoroutineContext().isActive) {
        delay(5000)
        val stats = suspendCancellableCoroutine { cont ->
            room.getPublisherRTCStats { cont.resume(it) }
        }

        Timber.e { "stats for publisher:" }

        for (entry in stats.statsMap) {
            Timber.e { "${entry.key} = ${entry.value}" }
        }
    }
}


suspend fun collectSubscriberMetrics(room: Room, rtcEngine: RTCEngine) {
    while (currentCoroutineContext().isActive) {
        delay(1000)
        val report = suspendCancellableCoroutine { cont ->
            room.getSubscriberRTCStats { cont.resume(it) }
        }

        Timber.e { "stats for subscriber:" }

        for (entry in report.statsMap) {
            Timber.e { "${entry.key} = ${entry.value}" }
        }

        val strings = mutableListOf<String>()
        val stats = findSubscriberAudioStats(strings, report, room.localParticipant.identity) +
            findSubscriberVideoStats(strings, report, room.localParticipant.identity)

        val dataPacket = with(LivekitModels.DataPacket.newBuilder()) {
            metrics = with(MetricsBatch.newBuilder()) {
                timestampMs = report.timestampUs.microToMilli()
                addAllStrData(strings)
                addAllTimeSeries(stats)
                build()
            }
            kind = DataPacket.Kind.LOSSY
            build()
        }

        rtcEngine.sendData(dataPacket)

        LKLog.e { "subscriber metrics: \n $dataPacket" }
    }
}

fun findSubscriberAudioStats(strings: MutableList<String>, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
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

fun findSubscriberVideoStats(strings: MutableList<String>, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
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

private fun MutableList<String>.getOrCreateIndex(string: String): Int {
    val index = indexOf(string)

    return if (index != -1) {
        index
    } else {
        // Doesn't exist, create.
        add(string)
        size - 1
    }
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
            // TODO specify rid
        }
        this.addAllSamples(samples)
        build()
    }
}


private fun Number.microToMilli(): Long {
    return TimeUnit.MILLISECONDS.convert(this.toLong(), TimeUnit.MILLISECONDS)
}

private enum class RTCMetric(val protoLabel: MetricLabel, val statKey: String) {
    FREEZE_COUNT(MetricLabel.AGENTS_LLM_TTFT, "freezeCount"),
    TOTAL_FREEZES_DURATION(MetricLabel.AGENTS_LLM_TTFT, "totalFreezesDuration"),
    PAUSE_COUNT(MetricLabel.AGENTS_LLM_TTFT, "pauseCount"),
    TOTAL_PAUSES_DURATION(MetricLabel.AGENTS_LLM_TTFT, "totalPausesDuration"),

    CONCEALED_SAMPLES(MetricLabel.AGENTS_LLM_TTFT, "concealedSamples"),
    SILENT_CONCEALED_SAMPLES(MetricLabel.AGENTS_LLM_TTFT, "silentConcealedSamples"),
    CONCEALMENT_EVENTS(MetricLabel.AGENTS_LLM_TTFT, "concealmentEvents"),

    JITTER_BUFFER_DELAY(MetricLabel.AGENTS_LLM_TTFT, "jitterBufferDelay"),

    QUALITY_LIMITATION_DURATIONS(MetricLabel.AGENTS_LLM_TTFT, "qualityLimitationDurations"),

}
