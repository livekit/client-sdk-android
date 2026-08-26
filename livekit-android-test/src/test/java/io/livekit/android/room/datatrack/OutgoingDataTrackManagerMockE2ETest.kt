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

import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.ReconnectType
import io.livekit.android.room.SignalClient
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.SignalRequestHandler
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.e2ee.NoopKeyProvider
import io.livekit.android.test.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.yield
import livekit.LivekitRtc
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.PeerConnection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun publishDataTrackPassesEncryptionProviderWhenE2eeEnabled() = runTest {
        room.e2eeOptions = E2EEOptions(keyProvider = NoopKeyProvider())
        connect()

        val result = room.localParticipant.publishDataTrack("telemetry")
        assertTrue(result.isSuccess)

        // Tests use ReversingDataPacketCryptorManager by default.
        val encryptionProvider = localDataTrackManagerFactory.lastEncryptionProvider
        assertNotNull(encryptionProvider)
        val encrypted = encryptionProvider!!.encrypt(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(3, 2, 1), encrypted.payload)

        val decryptionProvider = remoteDataTrackManagerFactory.lastDecryptionProvider
        assertNotNull(decryptionProvider)
        val decrypted = decryptionProvider!!.decrypt(
            encrypted,
            room.localParticipant.identity!!.value,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), decrypted)
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
    fun publishDataTrackWaitsForReplacementChannelAcrossFullReconnect() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        wsFactory.registerSignalRequestHandler(publisherOfferHandler)
        connect()
        publisherDataTrackChannel().state = DataChannel.State.CONNECTING

        val publish = async { room.localParticipant.publishDataTrack("telemetry") }
        yield()
        assertTrue(publish.isActive)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        yield()
        assertTrue(publish.isActive)

        reconnectWebsocket()
        connectPeerConnection()
        advanceUntilIdle()

        val result = publish.await()
        assertTrue(result.isSuccess)
        assertEquals("telemetry", result.getOrThrow().info.name)
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

    @Test
    fun fullReconnectSendsDataTrackPacketsOnReplacementChannel() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        wsFactory.registerSignalRequestHandler(publisherOfferHandler)
        connect()
        val original = publisherDataTrackChannel()
        room.engine.sendDataTrackPackets(listOf(byteArrayOf(1)))
        assertEquals(1, original.sentPayloads.size)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        advanceUntilIdle()

        val replacement = publisherDataTrackChannel()
        assertNotSame(original, replacement)
        room.engine.sendDataTrackPackets(listOf(byteArrayOf(2)))
        advanceUntilIdle()
        assertEquals(1, replacement.sentPayloads.size)
        assertArrayEquals(byteArrayOf(2), replacement.sentPayloads.single())
    }

    @Test
    fun fullReconnectRenegotiatesPublisherForDataTrack() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        wsFactory.registerSignalRequestHandler(publisherOfferHandler)
        connect()

        val result = room.localParticipant.publishDataTrack("telemetry")
        assertTrue(result.isSuccess)

        val local = localDataTrackManagerFactory.manager
        assertEquals(0, local.republishTracksCount)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        advanceUntilIdle()

        assertEquals(1, local.republishTracksCount)
        assertEquals(
            PeerConnection.PeerConnectionState.CONNECTED,
            getPublisherPeerConnection().connectionState(),
        )
    }

    @Test
    fun softReconnectIncludesPublishedDataTracksInSyncState() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)
        wsFactory.registerSignalRequestHandler(publisherOfferHandler)
        connect()

        val result = room.localParticipant.publishDataTrack("telemetry")
        assertTrue(result.isSuccess)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        advanceUntilIdle()

        val syncState = wsFactory.ws.sentRequests
            .map { LivekitRtc.SignalRequest.parseFrom(it.toPBByteString()) }
            .firstOrNull { it.hasSyncState() }
            ?.syncState
        assertNotNull(syncState)
        assertEquals(1, syncState!!.publishDataTracksCount)
        assertEquals("telemetry", syncState.getPublishDataTracks(0).info.name)
        assertEquals("DT_mock", syncState.getPublishDataTracks(0).info.sid)
    }

    private val publisherOfferHandler: SignalRequestHandler = { request ->
        if (request.hasOffer()) {
            val answer = with(LivekitRtc.SignalResponse.newBuilder()) {
                answer = with(LivekitRtc.SessionDescription.newBuilder()) {
                    sdp = "remote_answer"
                    type = "answer"
                    id = request.offer.id
                    build()
                }
                build()
            }
            wsFactory.receiveMessage(answer)
            true
        } else {
            false
        }
    }

    private fun publisherDataTrackChannel() =
        getPublisherPeerConnection().dataChannels[RTCEngine.DATA_TRACK_DATA_CHANNEL_LABEL] as MockDataChannel

    private fun reconnectWebsocket() {
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        val softReconnectParam = wsFactory.request.url
            .queryParameter(SignalClient.CONNECT_QUERY_RECONNECT)
            ?.toIntOrNull()
            ?: 0

        if (softReconnectParam == 0) {
            simulateMessageFromServer(TestData.JOIN)
        } else {
            simulateMessageFromServer(TestData.RECONNECT)
        }
    }
}
