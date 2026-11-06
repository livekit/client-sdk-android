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

import android.javax.sdp.SdpFactory
import io.livekit.android.room.util.MediaConstraintKeys
import io.livekit.android.room.util.findConstraint
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.MockRTCThreadToken
import io.livekit.android.util.Either
import io.livekit.android.webrtc.JainSdpUtilsTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MockPeerConnectionFactory
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnection.RTCConfiguration
import livekit.org.webrtc.PeerConnection.SignalingState
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.WebRTCInitializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class PeerConnectionTransportTest {

    private class RecordingListener : PeerConnectionTransport.Listener {
        val offers = mutableListOf<Pair<SessionDescription, Int>>()

        override fun onOffer(sd: SessionDescription, offerId: Int) {
            offers.add(sd to offerId)
        }
    }

    private val listener = RecordingListener()
    private lateinit var transport: PeerConnectionTransport

    private val pc: MockPeerConnection
        get() = transport.peerConnection as MockPeerConnection

    @Before
    fun setup() {
        WebRTCInitializer.initialize()
        transport = PeerConnectionTransport(
            config = RTCConfiguration(emptyList()),
            pcObserver = mock<PeerConnection.Observer>(),
            listener = listener,
            ioDispatcher = Dispatchers.Unconfined,
            connectionFactory = MockPeerConnectionFactory(),
            sdpFactory = SdpFactory.getInstance(),
            rtcThreadToken = MockRTCThreadToken(),
        )
        // The initial offer is stored munged, which needs an offer that parses as SDP.
        pc.offerDescription = JainSdpUtilsTest.DESCRIPTION
    }

    private fun answer() = SessionDescription(SessionDescription.Type.ANSWER, "remote_answer")

    private suspend fun initialOffer(constraints: MediaConstraints = MediaConstraints()): PeerConnectionTransport.InitialOffer {
        return when (val result = transport.createInitialOffer(constraints)) {
            is Either.Left -> result.value
            is Either.Right -> throw AssertionError("createInitialOffer failed: ${result.value}")
        }
    }

    /**
     * [PeerConnectionTransport.negotiate] is debounced on the real clock, so offers are awaited
     * rather than scheduled.
     */
    private suspend fun awaitOffers(count: Int) {
        withTimeout(OFFER_TIMEOUT_MS) {
            while (listener.offers.size < count) {
                delay(10)
            }
        }
    }

    // region createInitialOffer

    @Test
    fun initialOfferTakesTheFirstOfferId() = runTest {
        val offer = initialOffer()

        assertEquals(1, offer.offerId)
        assertEquals(SessionDescription.Type.OFFER, offer.sessionDescription.type)
        assertTrue(offer.sessionDescription.description.isNotEmpty())
    }

    @Test
    fun initialOfferUsesTheGivenConstraints() = runTest {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair(MediaConstraintKeys.OFFER_TO_RECV_AUDIO, MediaConstraintKeys.FALSE))
        }

        initialOffer(constraints)

        assertEquals(
            MediaConstraintKeys.FALSE,
            pc.lastOfferConstraints?.findConstraint(MediaConstraintKeys.OFFER_TO_RECV_AUDIO),
        )
    }

    @Test
    fun initialOfferIsMunged() = runTest {
        pc.offerDescription = SdpMungingTest.NO_DD_DESCRIPTION

        val offer = initialOffer()

        assertTrue(
            "AV1 section is missing the dependency descriptor extension",
            offer.sessionDescription.description.contains(DD_EXTENSION_URI),
        )
    }

    @Test
    fun initialOfferIsNotSetAsLocalDescription() = runTest {
        initialOffer()

        assertNull(pc.localDesc)
        assertEquals(SignalingState.STABLE, pc.signalingState())
    }

    @Test
    fun initialOfferIsNotSentToTheListener() = runBlocking {
        initialOffer()
        delay(OFFER_SETTLE_MS)

        assertTrue(listener.offers.isEmpty())
    }

    @Test
    fun initialOfferFailsOutsideStableState() = runTest {
        pc.setLocalDescription(NoopSdpObserver, SessionDescription(SessionDescription.Type.OFFER, "in_flight_offer"))
        assertEquals(SignalingState.HAVE_LOCAL_OFFER, pc.signalingState())

        assertTrue(transport.createInitialOffer() is Either.Right)
    }

    @Test
    fun initialOfferFailsWhenClosed() = runTest {
        transport.close()

        assertTrue(transport.createInitialOffer() is Either.Right)
    }

    // endregion

    // region answer

    @Test
    fun answerAppliesThePendingOfferFirst() = runTest {
        val offer = initialOffer()

        val result = transport.setRemoteDescription(answer(), offer.offerId)

        assertTrue(result is Either.Left)
        assertEquals(offer.sessionDescription.description, pc.localDesc?.description)
        assertEquals("remote_answer", pc.remoteDesc?.description)
        assertEquals(SignalingState.STABLE, pc.signalingState())
    }

    @Test
    fun answerWithoutAnOfferIdAppliesThePendingOffer() = runTest {
        val offer = initialOffer()

        val result = transport.setRemoteDescription(answer(), 0)

        assertTrue(result is Either.Left)
        assertEquals(offer.sessionDescription.description, pc.localDesc?.description)
    }

    @Test
    fun negotiationWaitsForThePendingOfferToBeAnswered() = runBlocking {
        val offer = initialOffer()

        transport.negotiate(null)
        delay(OFFER_SETTLE_MS)
        assertTrue("offered over the pending initial offer", listener.offers.isEmpty())
        assertEquals(SignalingState.STABLE, pc.signalingState())

        assertTrue(transport.setRemoteDescription(answer(), offer.offerId) is Either.Left)

        awaitOffers(1)
        assertEquals(2, listener.offers.single().second)
        assertEquals(SignalingState.HAVE_LOCAL_OFFER, pc.signalingState())
    }

    // endregion

    // region clearPendingInitialOffer

    @Test
    fun clearingThePendingOfferResumesNegotiation() = runBlocking {
        initialOffer()
        transport.clearPendingInitialOffer()

        transport.negotiate(null)

        awaitOffers(1)
        assertEquals(2, listener.offers.single().second)
        assertEquals(SignalingState.HAVE_LOCAL_OFFER, pc.signalingState())
    }

    // endregion

    private object NoopSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val OFFER_TIMEOUT_MS = 2_000L
        private const val DD_EXTENSION_URI = "https://aomediacodec.github.io/av1-rtp-spec/#dependency-descriptor-rtp-header-extension"

        /** Comfortably longer than the negotiate debounce. */
        private const val OFFER_SETTLE_MS = 200L
    }
}
