/*
 * Copyright 2025-2026 LiveKit, Inc.
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

package io.livekit.android.room.track.screencapture

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.test.BaseTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenCaptureConnectionTest : BaseTest() {

    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun connectService() {
        shadowOf(application).boundServiceConnections.single().onServiceConnected(
            ComponentName(application, ScreenCaptureService::class.java),
            ScreenCaptureService().onBind(null),
        )
    }

    private fun disconnectService() {
        shadowOf(application).boundServiceConnections.single().onServiceDisconnected(
            ComponentName(application, ScreenCaptureService::class.java),
        )
    }

    @Test
    fun cancellingConnectUnbindsBeforeServiceConnects() = runTest {
        val screenCaptureConnection = ScreenCaptureConnection(application)

        val connectJob = launch { screenCaptureConnection.connect() }
        assertEquals(1, shadowOf(application).boundServiceConnections.size)

        // The caller that requested the bind is not guaranteed to survive to call stop().
        connectJob.cancelAndJoin()

        assertEquals(1, shadowOf(application).unboundServiceConnections.size)
        assertEquals(0, shadowOf(application).boundServiceConnections.size)
    }

    @Test
    fun connectRebindsAfterAnEarlierConnectWasCancelled() = runTest {
        val screenCaptureConnection = ScreenCaptureConnection(application)

        launch { screenCaptureConnection.connect() }.cancelAndJoin()
        assertEquals(0, shadowOf(application).boundServiceConnections.size)

        val connectJob = launch { screenCaptureConnection.connect() }

        // A cancelled connect must not leave the state claiming a bind is still in flight,
        // which would leave this caller waiting on a connection that was already released.
        assertEquals(1, shadowOf(application).boundServiceConnections.size)

        connectJob.cancelAndJoin()
    }

    @Test
    fun cancellingConnectAfterServiceConnectsUnbindsBeforeResumption() = runTest {
        val screenCaptureConnection = ScreenCaptureConnection(application)
        val connectJob = launch(StandardTestDispatcher(testScheduler)) {
            screenCaptureConnection.connect()
        }
        runCurrent()

        connectService()
        connectJob.cancel()

        runCurrent()
        connectJob.join()
        assertEquals(0, shadowOf(application).boundServiceConnections.size)
    }

    @Test
    fun connectKeepsServiceBoundOnceConnected() = runTest {
        val screenCaptureConnection = ScreenCaptureConnection(application)
        val connectJob = launch(StandardTestDispatcher(testScheduler)) {
            screenCaptureConnection.connect()
        }
        runCurrent()

        connectService()
        runCurrent()
        connectJob.join()

        assertTrue(screenCaptureConnection.isBound)
        assertEquals(1, shadowOf(application).boundServiceConnections.size)
    }

    @Test
    fun cancellingConnectKeepsBindingHeldByAnEarlierCaller() = runTest {
        val screenCaptureConnection = ScreenCaptureConnection(application)
        val firstConnect = launch(StandardTestDispatcher(testScheduler)) {
            screenCaptureConnection.connect()
        }
        runCurrent()
        connectService()
        runCurrent()
        firstConnect.join()

        // A dropped service connection leaves the binding in place, so a second caller waits on
        // it rather than binding again.
        disconnectService()
        val secondConnect = launch(StandardTestDispatcher(testScheduler)) {
            screenCaptureConnection.connect()
        }
        runCurrent()
        secondConnect.cancelAndJoin()

        // Releasing on cancellation must not tear down a binding the first caller still holds.
        assertEquals(1, shadowOf(application).boundServiceConnections.size)
    }

    @Test
    fun nullBindingFailsPendingConnect() = runTest {
        val screenCaptureConnection = ScreenCaptureConnection(application)

        var failure: Throwable? = null
        val connectJob = launch {
            failure = runCatching { screenCaptureConnection.connect() }.exceptionOrNull()
        }
        shadowOf(application).boundServiceConnections.single().onNullBinding(
            ComponentName(application, ScreenCaptureService::class.java),
        )

        connectJob.join()
        // A null binding is never followed by onServiceConnected, so the caller must fail
        // rather than stay suspended.
        assertTrue(failure is IllegalStateException)
        assertEquals(0, shadowOf(application).boundServiceConnections.size)
    }

    @Test
    fun deadBindingFailsPendingConnectAndReleasesTheBinding() = runTest {
        val screenCaptureConnection = ScreenCaptureConnection(application)

        var failure: Throwable? = null
        val connectJob = launch {
            failure = runCatching { screenCaptureConnection.connect() }.exceptionOrNull()
        }
        shadowOf(application).boundServiceConnections.single().onBindingDied(
            ComponentName(application, ScreenCaptureService::class.java),
        )

        connectJob.join()
        assertTrue(failure is IllegalStateException)

        // A dead binding never reconnects, so the next caller must bind fresh instead of
        // waiting on it.
        val secondConnect = launch { screenCaptureConnection.connect() }
        assertEquals(1, shadowOf(application).boundServiceConnections.size)
        secondConnect.cancelAndJoin()
    }

    @Test
    fun stopEndsPendingConnect() = runTest {
        val screenCaptureConnection = ScreenCaptureConnection(application)

        val connectJob = launch { screenCaptureConnection.connect() }
        screenCaptureConnection.stop()

        connectJob.join()
        assertEquals(0, shadowOf(application).boundServiceConnections.size)
    }
}
