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
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockDataChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.yield
import livekit.org.webrtc.DataChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.livekit_datatrack.PublishException

@OptIn(ExperimentalCoroutinesApi::class)
class OutgoingDataTrackManagerMockE2ETest : MockE2ETest() {

    @Test
    fun publishDataTrackUsesInjectedLocalManager() = runTest {
        connect()

        val result = room.localParticipant.publishDataTrack("telemetry")
        assertTrue(result.isSuccess)
        assertEquals("telemetry", result.getOrThrow().info.name)

        val local = localDataTrackManagerFactory.manager
        assertEquals(1, local.publishedTracks.size)
        assertEquals("telemetry", local.publishedTracks.single().info().name)
        assertNull(localDataTrackManagerFactory.lastEncryptionProvider)
    }

    @Test
    fun publishDataTrackFailsWhenNativeLibraryUnavailable() = runTest {
        localDataTrackManagerFactory.createError = UnsatisfiedLinkError("dlopen failed")
        connect()

        val result = room.localParticipant.publishDataTrack("telemetry")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DataTrackPublishException.Internal)
    }

    /**
     * The core rejects a schema whose encoding cannot describe the track's frames (and the other
     * schema-metadata rules) before it allocates a handle. This covers the SDK's half: that the
     * refusal reaches the caller as a typed failure rather than an opaque one.
     */
    @Test
    fun publishDataTrackSurfacesInvalidSchemaFromTheCore() = runTest {
        localDataTrackManagerFactory.publishError =
            PublishException.InvalidSchema("Specified schema and frame encodings are incompatible")
        connect()

        val result = room.localParticipant.publishDataTrack(
            "telemetry",
            DataTrackPublishOptions(
                DataTrackFrameEncoding.Cdr,
                DataTrackSchemaId("reading.v1", DataTrackSchemaEncoding.JsonSchema),
            ),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DataTrackPublishException.InvalidSchema)
    }

    @Test
    fun publishDataTrackWaitsForPublisherChannelOpen() = runTest {
        connect()
        val channel = publisherDataTrackChannel()
        channel.state = DataChannel.State.CONNECTING

        val publish = async { room.localParticipant.publishDataTrack("telemetry") }
        yield()
        assertTrue(publish.isActive)

        channel.state = DataChannel.State.OPEN
        val result = publish.await()
        assertTrue(result.isSuccess)
    }

    @Test
    fun dataTrackPacketsWaitForLowWaterThenSend() = runTest {
        connect()
        val channel = publisherDataTrackChannel()
        channel.bufferedAmount = DataTrackFrameSender.LOW_WATER_MARK + 1

        room.engine.sendDataTrackPackets(listOf(byteArrayOf(1)))
        assertTrue(channel.sentPayloads.isEmpty())

        channel.bufferedAmount = 0
        advanceUntilIdle()
        assertEquals(1, channel.sentPayloads.size)
        assertArrayEquals(byteArrayOf(1), channel.sentPayloads.single())
    }

    @Test
    fun dataTrackPacketsDropOldestQueuedFrame() = runTest {
        connect()
        val channel = publisherDataTrackChannel()
        channel.bufferedAmount = DataTrackFrameSender.LOW_WATER_MARK + 1

        room.engine.sendDataTrackPackets(listOf(byteArrayOf(1)))
        room.engine.sendDataTrackPackets(listOf(byteArrayOf(2)))
        assertTrue(channel.sentPayloads.isEmpty())

        channel.bufferedAmount = 0
        advanceUntilIdle()
        assertEquals(1, channel.sentPayloads.size)
        assertArrayEquals(byteArrayOf(2), channel.sentPayloads.single())
    }

    @Test
    fun publishDataTrackTimesOutIfPublisherChannelNeverOpens() = runTest {
        connect()
        publisherDataTrackChannel().state = DataChannel.State.CONNECTING

        val result = room.localParticipant.publishDataTrack("telemetry")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DataTrackPublishException.Timeout)
    }

    @Test
    fun publishDataTrackFailsIfDisconnectedWhileWaitingForChannel() = runTest {
        connect()
        publisherDataTrackChannel().state = DataChannel.State.CONNECTING

        val publish = async { room.localParticipant.publishDataTrack("telemetry") }
        yield()
        assertTrue(publish.isActive)

        room.disconnect()
        advanceUntilIdle()

        val result = publish.await()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DataTrackPublishException.Disconnected)
    }

    private fun publisherDataTrackChannel() =
        getPublisherPeerConnection().dataChannels[RTCEngine.DATA_TRACK_DATA_CHANNEL_LABEL] as MockDataChannel
}
