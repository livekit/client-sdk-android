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

package io.livekit.android.room.participant

import android.Manifest
import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import io.livekit.android.room.ConnectionState
import io.livekit.android.room.track.LocalScreencastVideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.room.track.screencapture.ScreenCaptureService
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.camera.MockCameraProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import livekit.LivekitRtc
import livekit.org.webrtc.ScreenCapturerAndroid
import livekit.org.webrtc.SurfaceTextureHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Enabling a track suspends while publishing, so a cancellation can land after the track and its
 * resources were created. These tests pin that setTrackEnabled always tears down a track it could
 * not publish, at every suspension point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SetTrackEnabledCleanupMockE2ETest : MockE2ETest() {

    private lateinit var screenCapturers: MockedConstruction<ScreenCapturerAndroid>
    private var projectionCallback: LocalScreencastVideoTrack.MediaProjectionCallback? = null

    @Before
    fun mockScreenCapturerConstruction() {
        screenCapturers = mockConstruction(ScreenCapturerAndroid::class.java) { _, creation ->
            projectionCallback = creation.arguments()[1] as LocalScreencastVideoTrack.MediaProjectionCallback
        }
    }

    @After
    fun releaseScreenCapturerConstruction() {
        screenCapturers.close()
    }

    private val resumeLeave = with(LivekitRtc.SignalResponse.newBuilder()) {
        leave = with(LivekitRtc.LeaveRequest.newBuilder()) {
            action = LivekitRtc.LeaveRequest.Action.RESUME
            build()
        }
        build()
    }

    private fun screenCaptureParams(onStop: (() -> Unit)? = null) = ScreenCaptureParams(
        mediaProjectionPermissionResultData = Intent(),
        // An explicit notification, so the service does not reach for a notification manager.
        notificationId = 42,
        notification = Notification(),
        onStop = onStop,
    )

    private fun connectScreenCaptureService() {
        shadowOf(context as Application).boundServiceConnections.single().onServiceConnected(
            ComponentName(context, ScreenCaptureService::class.java),
            ScreenCaptureService().onBind(null),
        )
    }

    @Test
    fun cancelDuringScreenShareServiceBindCleansUpTrack() = runTest {
        connect()

        var onStopCalled = false
        val job = launch(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.setScreenShareEnabled(true, screenCaptureParams { onStopCalled = true })
        }
        // Suspended in startForegroundService, waiting for the service to connect.
        runCurrent()
        assertEquals(1, shadowOf(context as Application).boundServiceConnections.size)

        job.cancelAndJoin()

        val capturer = screenCapturers.constructed().single()
        verify(capturer, atLeastOnce()).stopCapture()
        verify(capturer).dispose()
        assertTrue(onStopCalled)
        assertEquals(0, shadowOf(context as Application).boundServiceConnections.size)
        assertTrue(room.localParticipant.trackPublications.isEmpty())
    }

    @Test
    fun cancelDuringScreenSharePublishCleansUpTrackAndUnbindsService() = runTest {
        connect()
        // Never answer the add track request, so publishing suspends until cancelled.
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)

        var onStopCalled = false
        val job = launch(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.setScreenShareEnabled(true, screenCaptureParams { onStopCalled = true })
        }
        runCurrent()
        connectScreenCaptureService()
        // Suspended in publishVideoTrack, with capture running and the service bound.
        runCurrent()
        val capturer = screenCapturers.constructed().single()
        verify(capturer, never()).stopCapture()

        job.cancelAndJoin()

        verify(capturer, atLeastOnce()).stopCapture()
        verify(capturer).dispose()
        assertTrue(onStopCalled)
        assertEquals(0, shadowOf(context as Application).boundServiceConnections.size)
        assertTrue(room.localParticipant.trackPublications.isEmpty())

        // Negotiation ran concurrently with the add track request, so the transceiver it
        // created must be rolled back; nothing else stops it once the track is abandoned.
        val transceivers = getPublisherPeerConnection().transceivers
        assertEquals(1, transceivers.size)
        verify(transceivers.single()).stopInternal()
    }

    @Test
    fun projectionStopDuringScreenSharePublishDeliversOnStopOnce() = runTest {
        connect()
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)

        var onStopCount = 0
        val job = launch(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.setScreenShareEnabled(true, screenCaptureParams { onStopCount++ })
        }
        runCurrent()
        connectScreenCaptureService()
        runCurrent()

        // The user ends the projection from the system UI while publishing is still in flight.
        projectionCallback!!.onStop()
        assertEquals(1, onStopCount)

        job.cancelAndJoin()

        assertEquals(1, onStopCount)
        assertTrue(room.localParticipant.trackPublications.isEmpty())
    }

    @Test
    fun projectionStopArrivingAfterCancelledEnableDoesNotThrow() = runTest {
        connect()
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)

        val job = launch(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.setScreenShareEnabled(true, screenCaptureParams())
        }
        runCurrent()
        connectScreenCaptureService()
        runCurrent()
        job.cancelAndJoin()

        // A projection stop that was already dispatched can arrive after cleanup disposed the
        // track, and the capturer throws once it is disposed. The callback must not propagate.
        val capturer = screenCapturers.constructed().single()
        doThrow(RuntimeException("capturer is disposed.")).`when`(capturer).stopCapture()
        projectionCallback!!.onStop()
    }

    @Test
    fun successfulScreenShareKeepsTrackAndServiceBinding() = runTest {
        connect()

        var onStopCalled = false
        val result = async(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.setScreenShareEnabled(true, screenCaptureParams { onStopCalled = true })
        }
        runCurrent()
        connectScreenCaptureService()
        advanceUntilIdle()

        assertTrue(result.await())
        val capturer = screenCapturers.constructed().single()
        verify(capturer, never()).stopCapture()
        verify(capturer, never()).dispose()
        assertFalse(onStopCalled)
        assertEquals(1, shadowOf(context as Application).boundServiceConnections.size)
        assertEquals(1, room.localParticipant.trackPublications.size)
    }

    @Test
    fun cancelDuringMicrophonePublishStopsTrackAndKeepsTransceiver() = runTest {
        connect()
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)

        val job = launch(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.setMicrophoneEnabled(true)
        }
        runCurrent()

        job.cancelAndJoin()

        assertFalse(room.localParticipant.getOrCreateDefaultAudioTrack().enabled)
        assertTrue(room.localParticipant.trackPublications.isEmpty())

        // Detaching the sender is the whole rollback for audio: a single m-section is a cheap
        // thing to leave behind, and stopping it renegotiates the publisher for no gain.
        val transceivers = getPublisherPeerConnection().transceivers
        assertEquals(1, transceivers.size)
        verify(transceivers.single(), never()).stopInternal()
    }

    @Test
    fun cancelDuringScreenSharePublishWhileReconnectingKeepsTransceiver() = runTest {
        connect()
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)

        val job = launch(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.setScreenShareEnabled(true, screenCaptureParams())
        }
        runCurrent()
        connectScreenCaptureService()
        runCurrent()

        // Losing the socket with the add track request outstanding is what fails the publish,
        // and it starts the reconnect that renegotiates this publisher.
        wsFactory.ws.cancel()
        runCurrent()

        job.cancelAndJoin()

        val transceivers = getPublisherPeerConnection().transceivers
        assertEquals(1, transceivers.size)
        verify(transceivers.single(), never()).stopInternal()
    }

    @Test
    fun resumeLeaveDuringScreenSharePublishKeepsTransceiver() = runTest {
        connect()
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)

        val job = launch(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.setScreenShareEnabled(true, screenCaptureParams())
        }
        runCurrent()
        connectScreenCaptureService()
        runCurrent()

        // A resume leave fails the pending publish and leaves the reconnect to the socket close,
        // so the publisher still reads as connected while the failure unwinds.
        simulateMessageFromServer(resumeLeave)
        runCurrent()
        job.join()

        val transceivers = getPublisherPeerConnection().transceivers
        assertEquals(1, transceivers.size)
        verify(transceivers.single(), never()).stopInternal()
    }

    @Test
    fun resumeLeaveKeepsTransceiverWhenCleanupTrailsTheNextSession() = runTest {
        connect()
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)

        val job = launch(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.setScreenShareEnabled(true, screenCaptureParams())
        }
        runCurrent()
        connectScreenCaptureService()
        runCurrent()

        simulateMessageFromServer(resumeLeave)
        // The failed publish stays queued until the next session is serving. A soft reconnect
        // keeps the publisher, so the transceiver this attempt created is the resumed session's
        // to negotiate, not this cleanup's to remove.
        component.rtcEngine().connectionState = ConnectionState.RESUMING
        component.rtcEngine().connectionState = ConnectionState.CONNECTED
        runCurrent()
        job.join()

        val transceivers = getPublisherPeerConnection().transceivers
        assertEquals(1, transceivers.size)
        verify(transceivers.single(), never()).stopInternal()
    }

    @Test
    fun cancelDuringCameraPublishStopsTrack() = runTest {
        connect()
        MockCameraProvider.register()
        shadowOf(context as Application).grantPermissions(Manifest.permission.CAMERA)
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)

        val job = launch(StandardTestDispatcher(testScheduler)) {
            room.localParticipant.setCameraEnabled(true)
        }
        runCurrent()

        job.cancelAndJoin()

        assertFalse(room.localParticipant.getOrCreateDefaultVideoTrack().enabled)
        assertTrue(room.localParticipant.trackPublications.isEmpty())
    }

    @Test
    fun disposeReleasesScreencastSurfaceTextureHelper() {
        val helper = mock(SurfaceTextureHelper::class.java)
        mockStatic(SurfaceTextureHelper::class.java).use { helperFactory ->
            helperFactory.`when`<SurfaceTextureHelper> {
                SurfaceTextureHelper.create(any(), anyOrNull())
            }.thenReturn(helper)

            val track = room.localParticipant.createScreencastTrack(
                mediaProjectionPermissionResultData = Intent(),
            ) {}
            track.dispose()
        }

        verify(helper).stopListening()
        verify(helper).dispose()
    }
}
