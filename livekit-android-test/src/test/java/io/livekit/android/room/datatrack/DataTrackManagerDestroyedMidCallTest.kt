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

package io.livekit.android.room.datatrack

import io.livekit.android.room.RTCEngine
import io.livekit.android.test.BaseTest
import io.livekit.android.test.mock.room.datatrack.MockLocalDataTrackManagerFactory
import io.livekit.android.test.mock.room.datatrack.MockRemoteDataTrackManagerFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import javax.inject.Provider

/**
 * [OutgoingDataTrackManager.republishTracks] and
 * [IncomingDataTrackManager.resendSubscriptionUpdates] run from the reconnect coroutine
 * ([RTCEngine] launches it on a `SupervisorJob() + ioDispatcher` scope with no
 * [kotlinx.coroutines.CoroutineExceptionHandler]). A throw there would neither fail the reconnect
 * nor be caught anywhere — it would reach the thread's uncaught handler, and the reconnect would
 * stop before `onPostReconnect`, leaving the engine wedged mid-flight.
 *
 * A concurrent `disconnect()` makes that reachable: both methods resolve their manager and then
 * call into it, and `close()` can land in between. uniffi's generated `callWithHandle` guard
 * answers a destroyed handle with `IllegalStateException("<name> object has already been
 * destroyed")`, which the mocks reproduce.
 *
 * These tests drive that exact interleaving and assert the call is contained.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataTrackManagerDestroyedMidCallTest : BaseTest() {

    @Test
    fun republishTracksSurvivesManagerDestroyedMidCall() = runTest {
        val factory = MockLocalDataTrackManagerFactory()
        val engine = mock<RTCEngine>()
        whenever(engine.e2EEManager).thenReturn(null)
        val manager = OutgoingDataTrackManager(Provider { engine }, factory)

        // Build the underlying manager, so republishTracks() has one to resolve.
        assertTrue(manager.publishTrack("telemetry").isSuccess)

        // The FFI object is destroyed while the SDK still holds a reference to it — what a
        // disconnect() racing the reconnect does.
        factory.manager.close()

        manager.republishTracks()

        // The call was attempted against the destroyed handle and refused, and the refusal did
        // not escape.
        assertTrue(factory.manager.closed)
        assertEquals(0, factory.manager.republishTracksCount)
    }

    @Test
    fun publishResponsesForSyncStateSurvivesManagerDestroyedMidCall() = runTest {
        val factory = MockLocalDataTrackManagerFactory()
        val engine = mock<RTCEngine>()
        whenever(engine.e2EEManager).thenReturn(null)
        val manager = OutgoingDataTrackManager(Provider { engine }, factory)

        assertTrue(manager.publishTrack("telemetry").isSuccess)
        factory.manager.close()

        // The resume drops data tracks from the sync state rather than dying mid-flight.
        assertEquals(emptyList<ByteArray>(), manager.publishResponsesForSyncState())
    }

    @Test
    fun resendSubscriptionUpdatesSurvivesManagerDestroyedMidCall() {
        val factory = MockRemoteDataTrackManagerFactory()
        val manager = IncomingDataTrackManagerImpl(Provider { mock<RTCEngine>() }, factory)

        // Build the underlying manager, so resendSubscriptionUpdates() has one to resolve.
        manager.handleSfuJoinResponse(ByteArray(0))
        assertEquals(1, factory.manager.handledJoinResponses.size)

        factory.manager.close()

        manager.resendSubscriptionUpdates()

        assertTrue(factory.manager.closed)
        assertEquals(0, factory.manager.resendSubscriptionUpdatesCount)
    }

    /**
     * Guards the guard: if the mock stopped reproducing uniffi's destroyed-handle behavior, the
     * two tests above would pass against an unguarded implementation.
     */
    @Test
    fun mockManagersRejectCallsAfterClose() {
        val localFactory = MockLocalDataTrackManagerFactory()
        val local = localFactory.create(mock(), null)
        (local as AutoCloseable).close()
        val localError = runCatching { local.republishTracks() }.exceptionOrNull()
        assertTrue(localError is IllegalStateException)
        assertTrue(localError!!.message!!.contains("already been destroyed"))

        val remoteFactory = MockRemoteDataTrackManagerFactory()
        val remote = remoteFactory.create(mock(), null)
        (remote as AutoCloseable).close()
        val remoteError = runCatching { remote.resendSubscriptionUpdates() }.exceptionOrNull()
        assertTrue(remoteError is IllegalStateException)
        assertTrue(remoteError!!.message!!.contains("already been destroyed"))
    }
}
