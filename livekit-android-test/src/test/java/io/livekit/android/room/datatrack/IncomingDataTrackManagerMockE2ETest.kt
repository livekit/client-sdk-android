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
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.org.webrtc.DataChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingDataTrackManagerMockE2ETest : MockE2ETest() {

    @Test
    fun subscriberDataTrackChannelForwardsPacketsToIncomingManager() = runTest {
        connect()
        val channel = openSubscriberDataTrackChannel()
        val payload = byteArrayOf(9, 8, 7)
        receiveDataTrackPacket(channel, payload)

        val packets = remoteDataTrackManagerFactory.manager.handledPackets
        assertEquals(1, packets.size)
        assertArrayEquals(payload, packets.single())
    }

    @Test
    fun connectForwardsJoinToInjectedRemoteManager() = runTest {
        connect()

        assertEquals(Room.State.CONNECTED, room.state)
        val remote = remoteDataTrackManagerFactory.manager
        assertTrue(remote.handledJoinResponses.isNotEmpty())
        assertArrayEquals(TestData.JOIN.toByteArray(), remote.handledJoinResponses.first())
    }

    /**
     * The join path and the `_data_track` receive path both build the UniFFI manager on demand,
     * and the receive path runs on a WebRTC callback thread. Neither may let a native-library
     * failure escape, or apps that never touch data tracks lose the connection or the process.
     */
    @Test
    fun nativeLibraryFailureLeavesTheRoomUsable() = runTest {
        remoteDataTrackManagerFactory.createError = UnsatisfiedLinkError("dlopen failed")

        connect()
        assertEquals(Room.State.CONNECTED, room.state)

        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()
        assertNotNull(remoteParticipant())

        // Received packets are dropped rather than thrown from the callback.
        val channel = openSubscriberDataTrackChannel()
        receiveDataTrackPacket(channel, byteArrayOf(1))
        advanceUntilIdle()
        assertEquals(Room.State.CONNECTED, room.state)
    }

    private fun openSubscriberDataTrackChannel(): MockDataChannel {
        val channel = MockDataChannel(RTCEngine.DATA_TRACK_DATA_CHANNEL_LABEL)
        getSubscriberPeerConnection().observer?.onDataChannel(channel)
        return channel
    }

    private fun receiveDataTrackPacket(channel: MockDataChannel, payload: ByteArray) {
        channel.simulateBufferReceived(
            DataChannel.Buffer(ByteBuffer.wrap(payload), true),
        )
    }

    private fun remoteParticipant() =
        room.remoteParticipants[Participant.Identity(TestData.REMOTE_PARTICIPANT.identity)]!!
}
