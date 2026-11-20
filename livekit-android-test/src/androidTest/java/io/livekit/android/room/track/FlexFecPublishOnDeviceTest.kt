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

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Publishes a synthetic video track to a real LiveKit server with [LiveKit.enableFlexFec], and
 * checks how FlexFEC is negotiated on the publisher peer connection.
 *
 * libwebrtc only protects a single media stream with FlexFEC, so a FlexFEC SSRC is only signaled
 * when the track is published without simulcast. The repairs themselves follow libwebrtc's
 * loss-based protection, so none are sent without loss, and the publisher's stats do not count
 * them. To see them arrive, run against a server with `rtc.flexfec.upstream_enabled`, add upstream
 * loss, and watch the server's `livekit_fec_total{direction="incoming"}` metric while the track is
 * held for `lkPublishSeconds`.
 *
 * Skipped unless the instrumentation arguments `lkUrl` and `lkPublisherToken` are given:
 *
 * ```
 * ./gradlew :livekit-android-test:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.livekit.android.room.track.FlexFecPublishOnDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.lkUrl=ws://192.168.1.2:7880 \
 *   -Pandroid.testInstrumentationRunnerArguments.lkPublisherToken=<publisher token> \
 *   -Pandroid.testInstrumentationRunnerArguments.lkPublishSeconds=30
 * ```
 */
@RunWith(AndroidJUnit4::class)
class FlexFecPublishOnDeviceTest {

    private companion object {
        const val TAG = "FlexFecPublishTest"
        const val WIDTH = 640
        const val HEIGHT = 480
        const val FPS = 30
        const val NEGOTIATION_MS = 3_000L
        const val STATS_INTERVAL_MS = 5_000L
        const val DEFAULT_PUBLISH_SECONDS = 10L
    }

    private data class PublisherSdp(val offer: String, val answer: String) {
        val offersFlexFec: Boolean
            get() = offer.lines().any { it.startsWith("a=rtpmap:") && "flexfec-03" in it }
        val signalsFlexFecSsrc: Boolean
            get() = offer.lines().any { it.startsWith("a=ssrc-group:FEC-FR") }
        val answersFlexFec: Boolean
            get() = answer.lines().any { it.startsWith("a=rtpmap:") && "flexfec-03" in it }
    }

    private lateinit var room: Room
    private lateinit var url: String
    private lateinit var token: String
    private var publishSeconds = DEFAULT_PUBLISH_SECONDS

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("lkUrl")
        val token = args.getString("lkPublisherToken")
        assumeTrue("lkUrl and lkPublisherToken instrumentation arguments are required", url != null && token != null)
        this.url = url!!
        this.token = token!!
        publishSeconds = args.getString("lkPublishSeconds")?.toLongOrNull() ?: DEFAULT_PUBLISH_SECONDS

        LiveKit.loggingLevel = LoggingLevel.INFO
        LiveKit.enableFlexFec = true
    }

    @After
    fun tearDown() {
        if (::room.isInitialized) {
            room.disconnect()
            room.release()
        }
    }

    @Test
    fun withoutSimulcastSignalsFlexFec(): Unit = runBlocking {
        val sdp = publish(simulcast = false)

        assertTrue("offer includes flexfec-03", sdp.offersFlexFec)
        assertTrue("offer signals a FEC-FR SSRC", sdp.signalsFlexFecSsrc)
        assertTrue("server accepts flexfec-03", sdp.answersFlexFec)
    }

    @Test
    fun withSimulcastDoesNotSignalFlexFec(): Unit = runBlocking {
        val sdp = publish(simulcast = true)

        assertFalse("libwebrtc only protects a single stream with FlexFEC", sdp.signalsFlexFecSsrc)
    }

    private suspend fun publish(simulcast: Boolean): PublisherSdp {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        room = LiveKit.create(context, RoomOptions(adaptiveStream = false))
        room.connect(url, token)

        val local = room.localParticipant
        val track = local.createVideoTrack("synthetic", NoiseCapturer())
        track.startCapture()
        local.publishVideoTrack(track, VideoTrackPublishOptions(simulcast = simulcast))
        delay(NEGOTIATION_MS)

        val sdp = requireNotNull(
            room.engine.transport?.publisher?.withPeerConnection {
                PublisherSdp(localDescription?.description.orEmpty(), remoteDescription?.description.orEmpty())
            },
        ) { "publisher peer connection is closed" }
        Log.i(
            TAG,
            "simulcast=$simulcast offersFlexFec=${sdp.offersFlexFec} " +
                "signalsFlexFecSsrc=${sdp.signalsFlexFecSsrc} answersFlexFec=${sdp.answersFlexFec}",
        )

        val end = SystemClock.elapsedRealtime() + publishSeconds * 1000
        var packetsSent = 0L
        while (SystemClock.elapsedRealtime() < end) {
            delay(STATS_INTERVAL_MS)
            val stats = track.getRTCStats() ?: continue
            val outbound = stats.statsMap.values.filter { it.type == "outbound-rtp" }
            packetsSent = outbound.sumOf { (it.members["packetsSent"] as? Number)?.toLong() ?: 0L }
            val retransmitted = outbound.sumOf { (it.members["retransmittedPacketsSent"] as? Number)?.toLong() ?: 0L }
            val fractionLost = stats.statsMap.values
                .filter { it.type == "remote-inbound-rtp" }
                .map { it.members["fractionLost"] }
            Log.i(
                TAG,
                "simulcast=$simulcast packetsSent=$packetsSent retransmitted=$retransmitted " +
                    "reportedFractionLost=$fractionLost",
            )
        }
        track.stopCapture()

        assertTrue("media is sent", packetsSent > 0)
        return sdp
    }

    /**
     * Pushes noisy I420 frames, so the encoder produces multi-packet frames without a camera.
     */
    private class NoiseCapturer : VideoCapturer {
        private var observer: CapturerObserver? = null
        private var executor: ScheduledExecutorService? = null
        private val random = Random(0)

        override fun initialize(helper: SurfaceTextureHelper?, context: Context?, observer: CapturerObserver) {
            this.observer = observer
        }

        override fun startCapture(width: Int, height: Int, framerate: Int) {
            observer?.onCapturerStarted(true)
            executor = Executors.newSingleThreadScheduledExecutor().also {
                it.scheduleAtFixedRate(::pushFrame, 0, 1000L / FPS, TimeUnit.MILLISECONDS)
            }
        }

        private fun pushFrame() {
            val buffer = JavaI420Buffer.allocate(WIDTH, HEIGHT)
            val luma = ByteArray(buffer.dataY.capacity())
            random.nextBytes(luma)
            buffer.dataY.put(luma)
            val frame = VideoFrame(buffer, 0, System.nanoTime())
            observer?.onFrameCaptured(frame)
            frame.release()
        }

        override fun stopCapture() {
            executor?.shutdownNow()
            executor = null
            observer?.onCapturerStopped()
        }

        override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit

        override fun dispose() {
            stopCapture()
        }

        override fun isScreencast(): Boolean = false
    }
}
