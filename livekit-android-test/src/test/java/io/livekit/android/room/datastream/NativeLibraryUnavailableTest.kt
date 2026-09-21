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

package io.livekit.android.room.datastream

import io.livekit.android.memory.CloseableManager
import io.livekit.android.room.RTCEngine
import io.livekit.android.test.BaseTest
import io.livekit.android.util.UniffiNativeLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitModels.DataPacket
import livekit.LivekitModels.DataStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.stub
import org.robolectric.RobolectricTestRunner

/**
 * What happens on a device where `liblivekit_uniffi` cannot be loaded -- an ABI the packaged APK
 * has no `.so` for, or an app whose `abiFilters` dropped it.
 *
 * [UniffiNativeLibrary] is injected and shared by every FFI subsystem in a Room, so these drive an
 * instance of it directly rather than trying to make a real load fail: the contract under test is
 * what the SDK does once the library is known to be unavailable, and faking the [LinkageError]
 * exercises exactly that without a second JVM.
 *
 * The rule this protects: losing the native library must cost the app data streams and data
 * tracks, not `LiveKit.create` and not the process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NativeLibraryUnavailableTest : BaseTest() {

    @Mock
    lateinit var engine: RTCEngine

    private lateinit var dataStreams: DataStreams
    private lateinit var nativeLibrary: UniffiNativeLibrary

    @Before
    fun setup() {
        engine.stub {
            onBlocking { waitForBufferStatusLow(any()) } doReturn Unit
            on { e2EEManager } doReturn null
        }
        nativeLibrary = UniffiNativeLibrary()
        dataStreams = DataStreams(
            engine = engine,
            closeableManager = CloseableManager(),
            nativeLibrary = nativeLibrary,
        )
    }

    @After
    fun tearDown() {
        dataStreams.close()
    }

    /** Simulates the library having already failed to load, as a first caller would find it. */
    private fun latchUnavailable() {
        val result = nativeLibrary.createOrNull<Any>(subsystem = "Test") {
            throw UnsatisfiedLinkError("no liblivekit_uniffi for this ABI")
        }
        assertNull(result)
        assertTrue(nativeLibrary.isUnavailable)
    }

    // region The latch itself

    /**
     * The same instance is injected into data streams and both data track managers, so one
     * subsystem discovering the failure spares the others from rediscovering it.
     */
    @Test
    fun aFailedLoadLatchesAndSkipsLaterAttempts() {
        latchUnavailable()

        // A second caller -- here standing in for the data track managers, which are handed the
        // same instance -- must not retry the load.
        var attempted = false
        val result = nativeLibrary.createOrNull(subsystem = "Data tracks") {
            attempted = true
            "would have worked"
        }

        assertNull("a latched failure should short-circuit", result)
        assertFalse("the latch should stop the second attempt running at all", attempted)
    }

    @Test
    fun anUnlatchedLoadRunsAndReturnsItsValue() {
        val result = nativeLibrary.createOrNull(subsystem = "Data streams") { "built" }

        assertEquals("built", result)
        assertFalse(nativeLibrary.isUnavailable)
    }

    /**
     * Only [LinkageError] means "the library is not there". A failure from the Rust side is a
     * real error and must not be swallowed, nor latch data streams off for the rest of the Room.
     */
    @Test
    fun anOrdinaryFailurePropagatesAndDoesNotLatch() {
        val thrown = runCatching {
            nativeLibrary.createOrNull<Any>(subsystem = "Data streams") {
                throw IllegalStateException("something else went wrong")
            }
        }.exceptionOrNull()

        assertTrue("expected the original exception, got $thrown", thrown is IllegalStateException)
        assertFalse(nativeLibrary.isUnavailable)
    }

    // endregion

    // region Data streams degrading

    /**
     * Construction is what happens during `LiveKit.create`, so it must survive a missing library.
     */
    @Test
    fun dataStreamsStillConstructAndClose() {
        latchUnavailable()

        val streams = DataStreams(
            engine = engine,
            closeableManager = CloseableManager(),
            nativeLibrary = nativeLibrary,
        )
        streams.close()
    }

    /**
     * Inbound packets arrive on a WebRTC callback thread, where a throw takes down the process
     * rather than surfacing anywhere the app can handle it. Dropping them is the only safe answer.
     */
    @Test
    fun incomingPacketsAreDroppedRatherThanThrowing() = runTest {
        latchUnavailable()

        dataStreams.handleIncoming(textHeader())

        assertEquals(0uL, dataStreams.openStreamCount())
    }

    @Test
    fun registeringHandlersStillWorks() {
        latchUnavailable()

        // Room registers the RPC handlers during construction, so this must not throw either.
        dataStreams.registerTextStreamHandler("topic") { _, _ -> }
        dataStreams.unregisterTextStreamHandler("topic")
    }

    /**
     * Sends cannot degrade silently -- they have to return something -- so they fail, but as a
     * [StreamException] like every other send failure rather than a raw [LinkageError] escaping
     * the SDK.
     */
    @Test
    fun sendsFailWithAStreamException() = runTest {
        latchUnavailable()

        val error = runCatching {
            dataStreams.sendText("hello", StreamTextOptions(topic = "topic"))
        }.exceptionOrNull()

        assertTrue("expected a StreamException, got $error", error is StreamException)
    }

    @Test
    fun openingAStreamFailsWithAStreamException() = runTest {
        latchUnavailable()

        val error = runCatching {
            dataStreams.streamBytes(StreamBytesOptions(topic = "topic"))
        }.exceptionOrNull()

        assertTrue("expected a StreamException, got $error", error is StreamException)
    }

    // endregion

    private fun textHeader(): DataPacket = DataPacket.newBuilder()
        .setParticipantIdentity("sender")
        .setStreamHeader(
            DataStream.Header.newBuilder()
                .setStreamId("stream-1")
                .setTopic("topic")
                .setTimestamp(0)
                .setMimeType("text/plain")
                .setTotalLength(100)
                .setTextHeader(
                    DataStream.TextHeader.newBuilder()
                        .setOperationType(DataStream.OperationType.CREATE)
                        .build(),
                )
                .build(),
        )
        .build()
}
