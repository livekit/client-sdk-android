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

import android.javax.sdp.MediaDescription
import android.javax.sdp.SdpFactory
import io.livekit.android.test.BaseTest
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.MockRTCThreadToken
import io.livekit.android.webrtc.getRtps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.WebRTCInitializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class OpusNackOfferTest : BaseTest() {
    @Before
    fun setup() {
        WebRTCInitializer.initialize()
    }

    @Test
    fun singlePeerConnectionOffersAdvertiseNackInEveryAudioDirection() = runTest {
        withTransport(singlePeerConnection = true) { transport, _, offers ->
            transport.negotiate(MediaConstraints())
            advanceUntilIdle()

            assertEquals(1, offers.size)
            assertAudioNack(offers.single().description, expectedSections = 3, enabled = true)
        }
    }

    @Test
    fun dualPeerConnectionOffersKeepNackDisabled() = runTest {
        withTransport(singlePeerConnection = false) { transport, _, offers ->
            transport.negotiate(MediaConstraints())
            advanceUntilIdle()

            assertEquals(1, offers.size)
            assertAudioNack(offers.single().description, expectedSections = 3, enabled = false)
        }
    }

    @Test
    fun sendOnlyOfferAdvertisesNackBeforeAnyReceiveSectionsExist() = runTest {
        withTransport(
            singlePeerConnection = true,
            offerDescription = OFFER.substringBefore("m=audio 9 UDP/TLS/RTP/SAVPF 111"),
        ) { transport, _, offers ->
            transport.negotiate(MediaConstraints())
            advanceUntilIdle()

            assertEquals(1, offers.size)
            assertAudioNack(offers.single().description, expectedSections = 1, enabled = true)
        }
    }

    @Test
    fun rejectedNackMungeFallsBackToTheOriginalOffer() = runTest {
        withTransport(singlePeerConnection = true) { transport, pc, offers ->
            var rejectedMungedOffer = false
            doAnswer { call ->
                val observer = call.getArgument<SdpObserver>(0)
                val description = call.getArgument<SessionDescription>(1)
                if (description.description.lineSequence().any { it == "a=rtcp-fb:109 nack" }) {
                    rejectedMungedOffer = true
                    observer.onSetFailure("NACK munging rejected")
                } else {
                    call.callRealMethod()
                }
                null
            }.whenever(pc).setLocalDescription(any(), any())

            transport.negotiate(MediaConstraints())
            advanceUntilIdle()

            assertEquals(1, offers.size)
            assertTrue("the munged offer must have been tried first", rejectedMungedOffer)
            assertEquals(OFFER, offers.single().description)
            assertAudioNack(offers.single().description, expectedSections = 3, enabled = false)
        }
    }

    private suspend fun TestScope.withTransport(
        singlePeerConnection: Boolean,
        offerDescription: String = OFFER,
        body: suspend TestScope.(PeerConnectionTransport, MockPeerConnection, List<SessionDescription>) -> Unit,
    ) {
        val pc = spy(MockPeerConnection(PeerConnection.RTCConfiguration(emptyList()), null))
        doAnswer { call ->
            call.getArgument<SdpObserver>(0).onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER, offerDescription))
            null
        }.whenever(pc).createOffer(any(), any())
        val factory = mock<PeerConnectionFactory>()
        whenever(factory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any<PeerConnection.Observer>())).thenReturn(pc)
        val offers = mutableListOf<SessionDescription>()
        val transport = PeerConnectionTransport(
            config = PeerConnection.RTCConfiguration(emptyList()),
            pcObserver = mock(),
            listener = object : PeerConnectionTransport.Listener {
                override fun onOffer(sd: SessionDescription, offerId: Int) {
                    offers.add(sd)
                }
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            connectionFactory = factory,
            sdpFactory = SdpFactory.getInstance(),
            rtcThreadToken = MockRTCThreadToken(),
            singlePeerConnection = singlePeerConnection,
        )
        try {
            body(transport, pc, offers)
            assertNotNull(pc.localDesc)
            assertEquals(offers.last(), pc.localDesc)
        } finally {
            transport.close()
        }
    }

    private fun assertAudioNack(sdp: String, expectedSections: Int, enabled: Boolean) {
        val audio = SdpFactory.getInstance().createSessionDescription(sdp)
            .getMediaDescriptions(true).filterIsInstance<MediaDescription>()
            .filter { it.media.mediaType == "audio" }
        assertEquals(expectedSections, audio.size)
        for (section in audio) {
            val opus = section.getRtps().map { it.second }.filter { it.codec.equals("opus", ignoreCase = true) }
            assertFalse(opus.isEmpty())
            for (mapping in opus) {
                val nack = "a=rtcp-fb:${mapping.payload} nack"
                assertEquals(enabled, section.toString().lineSequence().any { it == nack })
            }
        }
    }

    companion object {
        val OFFER = """
            v=0
            o=- 1 1 IN IP4 127.0.0.1
            s=-
            t=0 0
            m=audio 9 UDP/TLS/RTP/SAVPF 109
            a=mid:0
            a=sendonly
            a=rtpmap:109 opus/48000/2
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=mid:1
            a=recvonly
            a=rtpmap:111 opus/48000/2
            m=audio 9 UDP/TLS/RTP/SAVPF 112
            a=mid:2
            a=sendrecv
            a=rtpmap:112 opus/48000/2
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }
}
