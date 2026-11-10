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

import io.livekit.android.test.mock.createCandidatePairChangeEvent
import io.livekit.android.test.mock.createIceCandidate
import livekit.org.webrtc.PeerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedIceCandidatePairTest {

    @Test
    fun parsesServerReflexiveCandidate() {
        val sdp = "candidate:842163049 1 udp 1686052607 5.1.2.3 41234 typ srflx raddr 192.168.1.5 rport 41234 " +
            "generation 0 ufrag Kd2R network-id 2 network-cost 10"
        val info = createIceCandidate(
            sdp = sdp,
            sdpMid = "1",
            sdpMLineIndex = 1,
            serverUrl = "stun:stun.example.com:3478",
            adapterType = PeerConnection.AdapterType.WIFI,
        ).toIceCandidateInfo()

        assertEquals("srflx", info.type)
        assertEquals("udp", info.protocol)
        assertEquals("5.1.2.3", info.address)
        assertEquals(41234, info.port)
        assertEquals("842163049", info.foundation)
        assertEquals(1, info.component)
        assertEquals(1686052607L, info.priority)
        assertEquals(100L, info.typePreference)
        assertEquals(32542L, info.localPreference)
        assertEquals(255L, info.componentPreference)
        assertEquals("192.168.1.5", info.relatedAddress)
        assertEquals(41234, info.relatedPort)
        assertNull(info.tcpType)
        assertEquals(
            mapOf("generation" to "0", "ufrag" to "Kd2R", "network-id" to "2", "network-cost" to "10"),
            info.attributes,
        )
        assertEquals("1", info.sdpMid)
        assertEquals(1, info.sdpMLineIndex)
        assertEquals("stun:stun.example.com:3478", info.serverUrl)
        assertEquals(PeerConnection.AdapterType.WIFI, info.adapterType)
        assertEquals(sdp, info.raw)
    }

    @Test
    fun parsesTcpCandidateWithSdpPrefix() {
        val info = createIceCandidate("a=candidate:1 1 tcp 1518280447 192.168.1.5 9 typ host tcptype active generation 0")
            .toIceCandidateInfo()

        assertEquals("host", info.type)
        assertEquals("tcp", info.protocol)
        assertEquals("active", info.tcpType)
        assertEquals("1", info.foundation)
        assertEquals(90L, info.typePreference)
        assertNull(info.relatedAddress)
        assertNull(info.serverUrl)
        assertEquals(mapOf("generation" to "0"), info.attributes)
    }

    @Test
    fun malformedCandidateDoesNotThrow() {
        val info = createIceCandidate("candidate:abc").toIceCandidateInfo()

        assertEquals("abc", info.foundation)
        assertNull(info.type)
        assertNull(info.protocol)
        assertNull(info.address)
        assertNull(info.port)
        assertNull(info.priority)
        assertNull(info.typePreference)
        assertTrue(info.attributes.isEmpty())
        assertEquals("candidate:abc", info.raw)
    }

    @Test
    fun convertsChangeEvent() {
        val event = createCandidatePairChangeEvent(
            local = createIceCandidate("candidate:2 1 udp 41885439 3.3.3.3 50000 typ relay raddr 5.1.2.3 rport 41234"),
            remote = createIceCandidate("candidate:3 1 udp 2130706431 34.10.0.9 7881 typ host"),
            reason = "switch to relay",
            lastDataReceivedMs = 1234,
            estimatedDisconnectedTimeMs = 250,
        )

        val pair = event.toSelectedIceCandidatePair(IceTarget.SUBSCRIBER)

        assertEquals(IceTarget.SUBSCRIBER, pair.target)
        assertEquals("relay", pair.local.type)
        assertEquals(2L, pair.local.typePreference)
        assertEquals("host", pair.remote.type)
        assertEquals(126L, pair.remote.typePreference)
        assertEquals("switch to relay", pair.reason)
        assertEquals(1234, pair.lastDataReceivedMs)
        assertEquals(250, pair.estimatedDisconnectedTimeMs)
        assertTrue(pair.isRelayed)
    }

    @Test
    fun directPairIsNotRelayed() {
        val pair = createCandidatePairChangeEvent(
            local = createIceCandidate("candidate:1 1 udp 2130706431 192.168.1.5 41234 typ host"),
            remote = createIceCandidate("candidate:3 1 udp 2130706431 34.10.0.9 7881 typ host"),
        ).toSelectedIceCandidatePair(IceTarget.PUBLISHER)

        assertFalse(pair.isRelayed)
    }
}
