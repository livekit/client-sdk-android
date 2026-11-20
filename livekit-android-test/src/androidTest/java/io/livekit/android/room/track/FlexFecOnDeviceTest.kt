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

package io.livekit.android.room.track

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.RTCStatsReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/**
 * Subscribes to a video track published by another participant on a real LiveKit server, and
 * checks that the server sends FlexFEC repair packets as requested through
 * [RemoteTrackPublication.setFecProtection].
 *
 * Needs a server with `rtc.flexfec.downstream_enabled` and a participant already publishing video
 * in the room, so it is skipped unless the instrumentation arguments `lkUrl` and `lkToken` are
 * given:
 *
 * ```
 * ./gradlew :livekit-android-test:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.livekit.android.room.track.FlexFecOnDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.lkUrl=ws://192.168.1.2:7880 \
 *   -Pandroid.testInstrumentationRunnerArguments.lkToken=<subscriber token>
 * ```
 *
 * Repairs are counted per frame and rounded like libwebrtc's `NumFecPackets`, so the measured
 * ratios depend on the frame sizes and only approximate the nominal 15/25/35%. The highest layer
 * is subscribed throughout to keep the frame sizes comparable between modes. No loss is
 * simulated, so adaptive protection is only checked to stay below a fixed level.
 */
@RunWith(AndroidJUnit4::class)
class FlexFecOnDeviceTest {

    private companion object {
        const val TAG = "FlexFecOnDeviceTest"
        const val SUBSCRIBE_TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 2_000L
        const val MEASURE_MS = 6_000L
        const val STATS_POLL_MS = 500L
    }

    private data class Counters(val mediaPackets: Long, val fecPackets: Long, val fecDiscarded: Long)

    private data class Window(val mediaPackets: Long, val fecPackets: Long, val fecDiscarded: Long) {
        val fecRatio: Double
            get() = if (mediaPackets == 0L) 0.0 else fecPackets.toDouble() / mediaPackets
    }

    private lateinit var room: Room
    private lateinit var publication: RemoteTrackPublication
    private lateinit var trackSid: String

    @Before
    fun setUp(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("lkUrl")
        val token = args.getString("lkToken")
        assumeTrue("lkUrl and lkToken instrumentation arguments are required", url != null && token != null)

        LiveKit.loggingLevel = LoggingLevel.INFO
        LiveKit.enableFlexFec = true

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        room = LiveKit.create(context, RoomOptions(adaptiveStream = false))

        withTimeout(SUBSCRIBE_TIMEOUT_MS) {
            val subscribed = room.events.events
                .filterIsInstance<RoomEvent.TrackSubscribed>()
            val connect = async { subscribed.first { it.track is VideoTrack } }
            room.connect(url!!, token!!)
            val event = connect.await()
            publication = event.publication as RemoteTrackPublication
            trackSid = event.publication.sid
        }
        Log.i(TAG, "subscribed to video track $trackSid")

        publication.setVideoQuality(VideoQuality.HIGH)
        withTimeout(SUBSCRIBE_TIMEOUT_MS) {
            while (readCounters()?.mediaPackets?.let { it > 0 } != true) {
                delay(STATS_POLL_MS)
            }
        }
    }

    @After
    fun tearDown() {
        if (::room.isInitialized) {
            room.disconnect()
            room.release()
        }
    }

    @Test
    fun serverSendsRepairsAsRequested(): Unit = runBlocking {
        val disabledAtStart = measure("initial (not requested)")
        assertTrue("media is flowing", disabledAtStart.mediaPackets > 0)
        assertEquals("no repairs before FEC is requested", 0L, disabledAtStart.fecPackets)

        val fixedLow = measure(FecProtection.Fixed(FecProtectionLevel.LOW))
        val fixedMedium = measure(FecProtection.Fixed(FecProtectionLevel.MEDIUM))
        val fixedHigh = measure(FecProtection.Fixed(FecProtectionLevel.HIGH))
        val minimumMedium = measure(FecProtection.Minimum(FecProtectionLevel.MEDIUM))
        val adaptive = measure(FecProtection.Adaptive)
        val disabled = measure(FecProtection.Disabled)

        assertTrue("media keeps flowing", disabled.mediaPackets > 0)
        assertTrue("fixed LOW sends repairs", fixedLow.fecRatio >= 0.10)
        assertTrue("fixed MEDIUM protects at least as much as LOW", fixedMedium.fecRatio >= fixedLow.fecRatio * 0.9)
        assertTrue("fixed HIGH protects more than LOW", fixedHigh.fecRatio > fixedLow.fecRatio)
        assertTrue("fixed HIGH protects at least 20%", fixedHigh.fecRatio >= 0.20)
        assertEquals(
            "minimum MEDIUM matches fixed MEDIUM without loss",
            fixedMedium.fecRatio,
            minimumMedium.fecRatio,
            0.05,
        )
        assertTrue("adaptive without loss stays below fixed LOW", adaptive.fecRatio < fixedLow.fecRatio)
        assertEquals("disabling stops the repairs", 0L, disabled.fecPackets)
        listOf(fixedLow, fixedMedium, fixedHigh, minimumMedium).forEach {
            assertEquals("repairs are not discarded", 0L, it.fecDiscarded)
        }
    }

    private suspend fun measure(protection: FecProtection): Window {
        publication.setFecProtection(protection)
        assertEquals(protection, publication.fecProtection)
        return measure(protection.toString())
    }

    private suspend fun measure(label: String): Window {
        delay(SETTLE_MS)
        val start = requireNotNull(readCounters()) { "no inbound video stats" }
        delay(MEASURE_MS)
        val end = requireNotNull(readCounters()) { "no inbound video stats" }
        val window = Window(
            mediaPackets = end.mediaPackets - start.mediaPackets,
            fecPackets = end.fecPackets - start.fecPackets,
            fecDiscarded = end.fecDiscarded - start.fecDiscarded,
        )
        Log.i(
            TAG,
            "$label: media=${window.mediaPackets} fec=${window.fecPackets} " +
                "discarded=${window.fecDiscarded} ratio=${"%.3f".format(window.fecRatio)}",
        )
        return window
    }

    private suspend fun readCounters(): Counters? {
        val report = suspendCancellableCoroutine<RTCStatsReport> { cont ->
            room.getSubscriberRTCStats { cont.resume(it) }
        }
        val inbound = report.statsMap.values.firstOrNull {
            it.type == "inbound-rtp" && it.members["kind"] == "video"
        }
        val members = inbound?.members ?: return null
        return Counters(
            mediaPackets = members.long("packetsReceived"),
            fecPackets = members.long("fecPacketsReceived"),
            fecDiscarded = members.long("fecPacketsDiscarded"),
        )
    }

    private fun Map<String, Any>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
}
