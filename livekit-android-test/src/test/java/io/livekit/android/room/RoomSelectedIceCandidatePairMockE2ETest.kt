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

package io.livekit.android.room

import io.livekit.android.events.RoomEvent
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.createCandidatePairChangeEvent
import io.livekit.android.test.mock.createIceCandidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.org.webrtc.PeerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomSelectedIceCandidatePairMockE2ETest : MockE2ETest() {

    private fun changeEvent(localSdp: String, reason: String = "", estimatedDisconnectedTimeMs: Int = 0) =
        createCandidatePairChangeEvent(
            local = createIceCandidate(localSdp, adapterType = PeerConnection.AdapterType.WIFI),
            remote = createIceCandidate("candidate:3 1 udp 2130706431 34.10.0.9 7881 typ host"),
            reason = reason,
            estimatedDisconnectedTimeMs = estimatedDisconnectedTimeMs,
        )

    private fun collectedPairChanges(events: List<RoomEvent>) =
        events.filterIsInstance<RoomEvent.SelectedIceCandidatePairChanged>().map { it.pair }

    @Test
    fun emitsForPublisherAndSubscriber() = runTest {
        connect()
        val collector = EventCollector(room.events, coroutineRule.scope)

        getPublisherPeerConnection().observer?.onSelectedCandidatePairChanged(
            changeEvent("candidate:1 1 udp 2130706431 192.168.1.5 41234 typ host", reason = "initial"),
        )
        getSubscriberPeerConnection().observer?.onSelectedCandidatePairChanged(
            changeEvent("candidate:2 1 udp 41885439 3.3.3.3 50000 typ relay", reason = "switch", estimatedDisconnectedTimeMs = 120),
        )

        val pairs = collectedPairChanges(collector.stopCollecting())
        assertEquals(listOf(IceTarget.PUBLISHER, IceTarget.SUBSCRIBER), pairs.map { it.target })

        val publisherPair = pairs[0]
        assertEquals("host", publisherPair.local.type)
        assertEquals("192.168.1.5", publisherPair.local.address)
        assertEquals(PeerConnection.AdapterType.WIFI, publisherPair.local.adapterType)
        assertEquals("34.10.0.9", publisherPair.remote.address)
        assertEquals("initial", publisherPair.reason)

        val subscriberPair = pairs[1]
        assertTrue(subscriberPair.isRelayed)
        assertEquals("switch", subscriberPair.reason)
        assertEquals(120, subscriberPair.estimatedDisconnectedTimeMs)
    }

    @Test
    fun ignoresNullEvent() = runTest {
        connect()
        val collector = EventCollector(room.events, coroutineRule.scope)

        getPublisherPeerConnection().observer?.onSelectedCandidatePairChanged(null)

        assertTrue(collectedPairChanges(collector.stopCollecting()).isEmpty())
    }

    @Test
    fun emitsAfterFullReconnect() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()
        val oldSubscriber = getSubscriberPeerConnection()

        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.JOIN)
        connectPeerConnection()
        testScheduler.advanceUntilIdle()

        val newSubscriber = getSubscriberPeerConnection()
        assertNotSame(oldSubscriber, newSubscriber)

        val collector = EventCollector(room.events, coroutineRule.scope)
        newSubscriber.observer?.onSelectedCandidatePairChanged(
            changeEvent("candidate:1 1 udp 2130706431 10.0.0.2 41234 typ host"),
        )

        val pairs = collectedPairChanges(collector.stopCollecting())
        assertEquals(1, pairs.size)
        assertEquals(IceTarget.SUBSCRIBER, pairs[0].target)
        assertEquals("10.0.0.2", pairs[0].local.address)
    }
}
