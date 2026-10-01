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

package io.livekit.android.telemetry

import android.media.AudioManager
import com.twilio.audioswitch.AudioDeviceChangeListener
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.network.ReconnectContext
import io.livekit.android.room.network.ReconnectPolicy
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.MockEglBase
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.MockRTCThreadToken
import io.livekit.android.test.mock.MockVideoCapturer
import io.livekit.android.test.mock.MockVideoStreamTrack
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.room.track.createMockLocalAudioTrack
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import io.livekit.uniffi.TelemetryScope
import io.livekit.uniffi.TelemetrySpan
import io.livekit.uniffi.telemetryDisconnectReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsCollectorCallback
import livekit.org.webrtc.RTCStatsReport
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import uniffi.livekit_telemetry.DeviceEvent
import uniffi.livekit_telemetry.ExportException
import uniffi.livekit_telemetry.ExportRequest
import uniffi.livekit_telemetry.InternalException
import uniffi.livekit_telemetry.SpanName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import uniffi.livekit_telemetry.DisconnectReason as FfiDisconnectReason

/** The platform paths the end-to-end test cannot see: tokens, redirects, poll pacing, the opt-out's cache, shared audio listeners. */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class TelemetryPlatformTest : MockE2ETest() {

    @Test
    fun everyTokenReachesTheScope() = runTest {
        val scope = mock<TelemetryScope>()
        component.rtcEngine().telemetryScope = scope

        connect()
        simulateMessageFromServer(TestData.REFRESH_TOKEN)

        inOrder(scope) {
            verify(scope).setServer(TestData.EXAMPLE_URL, "token")
            verify(scope).setServer(TestData.EXAMPLE_URL, TestData.REFRESH_TOKEN.refreshToken)
        }
    }

    @Test
    fun wakesFasterThanTheIntervalNeverStarveThePolls() = runTest {
        val wake = Channel<Unit>(Channel.CONFLATED)
        var polls = 0
        val poller = launch { pollStats(interval = { 1000 }, wake = wake, now = { testScheduler.currentTime }) { polls++ } }
        repeat(50) { // a track event every 100 ms, for 5 s
            wake.trySend(Unit)
            delay(100)
        }
        poller.cancel()
        assertTrue("one poll per second whatever wakes it: $polls", polls >= 4)
    }

    @Test
    fun aConnectedRoomCollectsNothingAfterTheOptOut() = runTest {
        val wasDisabled = Telemetry.disabled
        Telemetry.disabled = false // an earlier test in this JVM may have opted out
        connect()
        val scope = mock<TelemetryScope>()
        doAnswer { 50L }.whenever(scope).statsPollIntervalMs()
        val subscriber = getSubscriberPeerConnection()
        subscriber.statsReport = RTCStatsReport(0, mapOf("IN" to RTCStats(0, "inbound-rtp", "IN", mapOf("bytesReceived" to 1L))))
        val connection = CoroutineScope(SupervisorJob())
        RTCTelemetry(room, scope).start(connection)
        try {
            repeat(100) { if (subscriber.statsRequests == 0) Thread.sleep(50) }
            assertTrue("the Room's stats are collected while telemetry is on", subscriber.statsRequests > 0)

            Telemetry.disabled = true // what disableTelemetry() sets before it returns
            Thread.sleep(200) // a poll already running finishes
            val collected = subscriber.statsRequests
            clearInvocations(scope)
            Thread.sleep(500) // ten poll intervals
            simulateMessageFromServer(TestData.PARTICIPANT_JOIN) // remote tracks after the opt-out

            assertEquals("no getStats() after the opt-out", collected, subscriber.statsRequests)
            verifyNoInteractions(scope)
        } finally {
            Telemetry.disabled = wasDisabled
            connection.cancel()
        }
    }

    @Test
    fun anOptOutDuringAPollStartsNoFurtherRequestAndSubmitsNothing() = runTest {
        val wasDisabled = Telemetry.disabled
        Telemetry.disabled = false // an earlier test in this JVM may have opted out
        // fastPublish: the publisher exists from the join, so a poll asks it first, then the subscriber.
        connect(TestData.JOIN.toBuilder().apply { join = join.toBuilder().setFastPublish(true).build() }.build())
        val scope = mock<TelemetryScope>()
        val publisher = getPublisherPeerConnection()
        val subscriber = getSubscriberPeerConnection()
        val report = RTCStatsReport(0, mapOf("IN" to RTCStats(0, "inbound-rtp", "IN", mapOf("bytesReceived" to 1L))))
        val rtc = RTCTelemetry(room, scope) // not started: the test runs one poll itself

        /** One poll, paused in [pc]'s answer while the opt-out happens, then run to its end. */
        fun pollOptingOutDuring(pc: MockPeerConnection) = runBlocking(Dispatchers.Default) {
            val held = CompletableDeferred<RTCStatsCollectorCallback>()
            pc.statsAnswer = { held.complete(it) }
            val poll = launch { rtc.recordPeerStats() }
            val answer = withTimeout(5_000) { held.await() }
            synchronized(Telemetry) { Telemetry.disabled = true } // disableTelemetry()'s platform half; the core's opt-out would last the JVM
            answer.onStatsDelivered(report)
            poll.join()
        }

        try {
            // Paused before the subscriber's request is initiated: it never is.
            pollOptingOutDuring(publisher)
            assertEquals("no getStats() begins after the opt-out", 0, subscriber.statsRequests)

            // Paused before the submit (both answers in hand): nothing reaches the core.
            Telemetry.disabled = false
            publisher.statsAnswer = { it.onStatsDelivered(report) }
            pollOptingOutDuring(subscriber)
            verify(scope, never()).recordPeerStats(any(), any(), anyOrNull())
        } finally {
            Telemetry.disabled = wasDisabled
        }
    }

    @Test
    fun aThrowingCoreNeverFailsLoggingDisconnectOrPublishCleanup() = runTest {
        val panic = Answer<Any> { throw InternalException("core panic") }
        val span = mock<TelemetrySpan>(defaultAnswer = panic)
        val scope = mock<TelemetryScope>(defaultAnswer = panic)
        doReturn(span).whenever(scope).start(any(), anyOrNull())
        val installed = Telemetry.installed
        val wasDisabled = Telemetry.disabled
        Telemetry.installed = true // so logs and device events reach the (throwing) core,
        Telemetry.disabled = false // whatever an earlier test in this JVM did
        // The app's logger throws on the very diagnostic that reports the core's failure.
        val appLogger = LKLog.logger
        val appLevel = LKLog.loggingLevel
        LKLog.loggingLevel = LoggingLevel.WARN
        var loggerThrew = 0
        LKLog.logger = object : LKLog.Logger {
            override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                if (t is InternalException) {
                    loggerThrew++
                    throw IllegalStateException("app logger failed")
                }
                appLogger?.log(priority, t, message)
            }
        }
        try {
            room.telemetryScope = scope
            component.rtcEngine().telemetryScope = scope
            room.localParticipant.telemetryScope = scope
            connect(TestData.JOIN.toBuilder().apply { join = join.toBuilder().setFastPublish(true).build() }.build())

            // An SDK warning from a Room handler (outside the telemetry package, which capture skips),
            // filed under the Room's throwing scope; and a device event.
            simulateMessageFromServer(
                LivekitRtc.SignalResponse.newBuilder()
                    .setTrackUnpublished(LivekitRtc.TrackUnpublishedResponse.newBuilder().setTrackSid("TR_unknown"))
                    .build(),
            )
            verify(scope, atLeastOnce()).log(any())
            Telemetry.deviceEvent(DeviceEvent.AudioInterruption(began = true))

            // A publish the server never answers, cancelled: the video transceiver is still rolled back.
            wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)
            val publish = launch(StandardTestDispatcher(testScheduler)) { room.localParticipant.publishVideoTrack(createVideoTrack()) }
            runCurrent()
            publish.cancelAndJoin()
            val transceiver = getPublisherPeerConnection().transceivers.single()
            verify(transceiver).stopInternal()

            // Disconnect: the engine still closes, and the Room says it disconnected.
            val subscriber = getSubscriberPeerConnection()
            val events = EventCollector(room.events, coroutineRule.scope)
            room.disconnect()
            assertEquals(PeerConnection.IceConnectionState.CLOSED, subscriber.iceConnectionState())
            assertTrue(events.stopCollecting().any { it is RoomEvent.Disconnected })
            assertTrue("the app's logger threw on the failure diagnostic", loggerThrew > 0)
        } finally {
            Telemetry.installed = installed
            Telemetry.disabled = wasDisabled
            LKLog.logger = appLogger
            LKLog.loggingLevel = appLevel
        }
    }

    private fun createVideoTrack() = LocalVideoTrack(
        capturer = MockVideoCapturer(),
        source = mock(),
        name = "",
        options = LocalVideoTrackOptions(isScreencast = false, deviceId = null, position = null, captureParams = VideoCaptureParameter(1280, 720, 30)),
        rtcTrack = MockVideoStreamTrack(),
        peerConnectionFactory = component.peerConnectionFactory(),
        context = context,
        eglBase = MockEglBase(),
        defaultsManager = DefaultsManager(),
        trackFactory = mock(),
        rtcThreadToken = MockRTCThreadToken(),
    )

    @Test
    fun optOutDeletesAPreviousLaunchsCache() {
        val cache = Telemetry.storageDirectory(context).apply { mkdirs() }
        cache.resolve("batch").writeText("unsent")
        Telemetry.disabled = true
        try {
            assertNull("no scope after the opt-out", Telemetry.scope(context))
            assertFalse("the cache left by a previous launch is gone", cache.exists())
        } finally {
            Telemetry.disabled = false
        }
    }

    @Test
    fun aSharedAudioHandlerHasOneListenerPairUntilItsLastRoomLeaves() {
        // An app-supplied handler is shared by every Room; route and focus are process events.
        val handler = mock<AudioSwitchHandler>()
        val first = handler.observeForTelemetry()
        val second = handler.observeForTelemetry()
        val route = argumentCaptor<AudioDeviceChangeListener>()
        val focus = argumentCaptor<AudioManager.OnAudioFocusChangeListener>()
        verify(handler, times(1)).registerAudioDeviceChangeListener(route.capture())
        verify(handler, times(1)).registerOnAudioFocusChangeListener(focus.capture())

        first()
        first() // a release is idempotent
        verify(handler, never()).unregisterAudioDeviceChangeListener(any())
        second()
        verify(handler).unregisterAudioDeviceChangeListener(route.firstValue)
        verify(handler).unregisterOnAudioFocusChangeListener(focus.firstValue)

        val third = handler.observeForTelemetry() // a later Room registers afresh
        verify(handler, times(2)).registerAudioDeviceChangeListener(any())
        third()
        verify(handler, times(2)).unregisterAudioDeviceChangeListener(any())
    }

    @Test
    fun aRoomCreatedFromAnAudioCallbackNeverDeadlocksTheLastRelease() {
        // The handler dispatches holding its listener lock; a callback there may create a Room
        // while another thread releases the last Room, whose unregister needs that lock.
        val listenerLock = Object()
        val unregistering = CountDownLatch(1)
        val handler = mock<AudioSwitchHandler>()
        doAnswer { synchronized(listenerLock) {} }.whenever(handler).registerAudioDeviceChangeListener(any())
        doAnswer {
            unregistering.countDown()
            synchronized(listenerLock) {}
        }.whenever(handler).unregisterAudioDeviceChangeListener(any())
        val last = handler.observeForTelemetry()
        var created: (() -> Unit)? = null

        val dispatch = thread {
            synchronized(listenerLock) {
                unregistering.await(5, TimeUnit.SECONDS)
                created = handler.observeForTelemetry() // a Room created from the route callback
            }
        }
        val release = thread { last() }
        dispatch.join(5_000)
        release.join(5_000)

        assertFalse("no deadlock", dispatch.isAlive || release.isAlive)
        // The release unregistered, then re-registered for the Room the callback created.
        verify(handler, times(2)).registerAudioDeviceChangeListener(any())
        created!!()
        verify(handler, times(2)).unregisterAudioDeviceChangeListener(any())
    }

    @Test
    fun aPublishCancelledBeforeItStartsLeavesNoOpenSpan() = runTest {
        val span = mock<TelemetrySpan>()
        val scope = mock<TelemetryScope>()
        doReturn(span).whenever(scope).start(any(), anyOrNull())
        room.localParticipant.telemetryScope = scope
        launch {
            coroutineContext.job.cancel() // wins before withContext runs the publish
            room.localParticipant.publishAudioTrack(createMockLocalAudioTrack())
        }.join()
        verify(scope).start(SpanName.Publish, null)
        verify(span).cancel()
    }

    @Test
    fun aConnectAttemptHandsOverItsServerBeforeTheJoin() = runTest {
        val scope = mock<TelemetryScope>()
        room.telemetryScope = scope // the engine has none: only Room.connect can call it
        connect()
        verify(scope).setServer(TestData.EXAMPLE_URL, "token")
    }

    @Test
    fun aPublishFilesItsWarningsUnderItsOwnSpanAndSkipsAnEndedParent() = runTest {
        val ended = mock<TelemetrySpan>()
        doReturn(true).whenever(ended).isEnded()
        val span = mock<TelemetrySpan>()
        val scope = mock<TelemetryScope>()
        doReturn(span).whenever(scope).start(any(), anyOrNull())
        val installed = Telemetry.installed
        val wasDisabled = Telemetry.disabled
        Telemetry.installed = true
        Telemetry.disabled = false
        room.localParticipant.telemetryScope = scope
        try {
            // Not connected, so the publish fails its permission check with a warning.
            withContext(Telemetry.currentSpan.asContextElement(ended)) {
                runCatching { room.localParticipant.publishAudioTrack(createMockLocalAudioTrack()) }
            }
            verify(scope).start(SpanName.Publish, null)
            verify(span, atLeastOnce()).context() // the warning asked lk.publish for its span id
        } finally {
            Telemetry.installed = installed
            Telemetry.disabled = wasDisabled
        }
    }

    @Test
    fun aReconnectThatGivesUpEndsTheSessionAsReconnectFailed() = runTest {
        val scope = mock<TelemetryScope>()
        room.telemetryScope = scope
        connect()
        component.rtcEngine().reconnectPolicy = object : ReconnectPolicy {
            override fun getNextRetryDelay(context: ReconnectContext): Duration? = null
        }
        disconnectPeerConnection()
        testScheduler.advanceUntilIdle()
        verify(scope).disconnected(FfiDisconnectReason.RECONNECT_FAILED)
    }

    @Test
    fun aServerLeaveKeepsItsProtocolReason() = runTest {
        assumeTrue("needs the core's protocol mapping", runCatching { telemetryDisconnectReason(0) }.isSuccess)
        val scope = mock<TelemetryScope>()
        room.telemetryScope = scope
        connect()
        simulateMessageFromServer(
            LivekitRtc.SignalResponse.newBuilder()
                .setLeave(LivekitRtc.LeaveRequest.newBuilder().setReason(LivekitModels.DisconnectReason.AGENT_ERROR))
                .build(),
        )
        verify(scope).disconnected(FfiDisconnectReason.AGENT_ERROR) // the SDK's own enum has no agent_error
    }

    @Test
    fun aManualSubscribeWakesThePollerAtOnce() = runTest {
        connect()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        val scope = mock<TelemetryScope>()
        var interval = 60_000L
        doAnswer { interval }.whenever(scope).statsPollIntervalMs()
        val subscriber = getSubscriberPeerConnection()
        subscriber.statsReport = RTCStatsReport(0, mapOf("IN" to RTCStats(0, "inbound-rtp", "IN", mapOf("bytesReceived" to 1L))))
        val rtc = RTCTelemetry(room, scope)
        component.rtcEngine().client.rtcTelemetry = rtc
        val wasDisabled = Telemetry.disabled
        Telemetry.disabled = false
        val connection = CoroutineScope(SupervisorJob())
        rtc.start(connection)
        try {
            Thread.sleep(300)
            assertEquals("idle: the next poll is a minute away", 0, subscriber.statsRequests)

            interval = 50 // what the core answers once a subscribe awaits first media
            val publication = room.remoteParticipants.values.single().trackPublications.values.first() as RemoteTrackPublication
            publication.setSubscribed(true)
            repeat(40) { if (subscriber.statsRequests == 0) Thread.sleep(50) }

            verify(scope).subscribeStarted(any())
            assertTrue("polled right after the manual subscribe", subscriber.statsRequests > 0)
        } finally {
            Telemetry.disabled = wasDisabled
            connection.cancel()
        }
    }

    @Test
    fun transportPassesAnswersThroughAndFollowsNoRedirect() = runTest {
        val server = MockWebServer()
        server.start()
        val transport = OkHttpTelemetryTransport()
        val request = ExportRequest(server.url("/v1/logs").toString(), mapOf("Authorization" to "Bearer token"), byteArrayOf(1, 2, 3))

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("quota"))
        val throttled = transport.send(request)
        assertEquals(429, throttled.status.toInt())
        assertEquals("7", throttled.headers["Retry-After"])
        assertArrayEquals("quota".toByteArray(), throttled.body)
        assertEquals("Bearer token", server.takeRequest().getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))
        assertEquals("a redirect is the answer, never followed", 307, transport.send(request).status.toInt())
        assertEquals("the redirect target is never requested", 2, server.requestCount)

        server.shutdown()
        val noAnswer = runCatching { transport.send(request) }.exceptionOrNull()
        assertTrue("no answer is the transport's only error: $noAnswer", noAnswer is ExportException.Retryable)
    }
}
