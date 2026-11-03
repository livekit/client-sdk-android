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
import io.livekit.android.room.util.PeerConnectionStateObservable
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.MockRTCThreadToken
import io.livekit.android.test.mock.MockRtpReceiver
import io.livekit.android.util.Either
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.flowDelegate
import io.livekit.android.webrtc.toProtoSessionDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import livekit.LivekitRtc.MediaSectionsRequirement
import livekit.LivekitRtc.SignalTarget
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaStreamTrack.MediaType
import livekit.org.webrtc.MockPeerConnectionFactory
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnection.IceConnectionState
import livekit.org.webrtc.PeerConnection.PeerConnectionState
import livekit.org.webrtc.PeerConnection.RTCConfiguration
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver.RtpTransceiverDirection
import livekit.org.webrtc.RtpTransceiver.RtpTransceiverInit
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.WebRTCInitializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class SinglePeerConnectionTransportTest {

    private class FakeConnectionState : PeerConnectionStateObservable {
        @FlowObservable
        @get:FlowObservable
        override var connectionState by flowDelegate(PeerConnectionState.NEW)
    }

    private lateinit var publisher: PeerConnectionTransport
    private val publisherState = FakeConnectionState()

    private val publisherPc: MockPeerConnection
        get() = publisher.peerConnection as MockPeerConnection

    private val candidate = IceCandidate("0", 0, "candidate:1 1 udp 2122260223 192.168.1.2 50000 typ host")

    @Before
    fun setup() {
        WebRTCInitializer.initialize()
        publisher = spy(
            PeerConnectionTransport(
                config = RTCConfiguration(emptyList()),
                pcObserver = mock<PeerConnection.Observer>(),
                listener = null,
                ioDispatcher = Dispatchers.Unconfined,
                connectionFactory = MockPeerConnectionFactory(),
                sdpFactory = SdpFactory.getInstance(),
                rtcThreadToken = MockRTCThreadToken(),
            ),
        )
    }

    private fun transport() = SinglePeerConnectionTransport(
        publisher = publisher,
        publisherState = publisherState,
    )

    private fun answer(sdp: String = "remote_answer") = SessionDescription(SessionDescription.Type.ANSWER, sdp)

    // region roles

    @Test
    fun publisherIsThePeerConnection() {
        assertSame(publisher, transport().publisher)
    }

    @Test
    fun subscriberIsThePublisher() {
        assertSame(publisher, transport().subscriber)
    }

    @Test
    fun publisherConnectsOnJoin() {
        assertFalse(transport().publisherConnectsOnDemand)
    }

    @Test
    fun publisherOffersHaveNoConstraints() {
        assertTrue(transport().publisherOfferConstraints.isEmpty())
    }

    @Test
    fun publisherIsNegotiatedOnJoin() {
        assertTrue(transport().shouldNegotiatePublisherOnJoin(hasPublished = false))
        assertTrue(transport().shouldNegotiatePublisherOnJoin(hasPublished = true))
    }

    // endregion

    // region addIceCandidate

    @Test
    fun publisherCandidateGoesToPublisher() {
        assertTrue(transport().addIceCandidate(candidate, SignalTarget.PUBLISHER))

        verify(publisher).addIceCandidate(candidate)
    }

    @Test
    fun subscriberCandidateGoesToPublisher() {
        assertTrue(transport().addIceCandidate(candidate, SignalTarget.SUBSCRIBER))

        verify(publisher).addIceCandidate(candidate)
    }

    @Test
    fun unrecognizedTargetCandidateIsRejected() {
        assertFalse(transport().addIceCandidate(candidate, SignalTarget.UNRECOGNIZED))

        verify(publisher, never()).addIceCandidate(any())
    }

    // endregion

    // region answerServerOffer

    @Test
    fun answerServerOfferIsRejected() = runTest {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "remote_offer")

        val result = transport().answerServerOffer(offer, offerId = 7)

        assertTrue("$result", result is Either.Right)
    }

    @Test
    fun answerServerOfferLeavesPublisherAlone() = runTest {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "remote_offer")

        transport().answerServerOffer(offer, offerId = 7)

        verifyBlocking(publisher, never()) { setRemoteDescription(any(), any()) }
        assertNull(publisherPc.remoteDesc)
        assertNull(publisherPc.localDesc)
    }

    // endregion

    // region applyServerAnswer

    @Test
    fun applyServerAnswerAppliesToPublisher() = runTest {
        val answer = answer()

        val result = transport().applyServerAnswer(answer, offerId = 7, midToTrackId = emptyMap())

        assertTrue("$result", result is Either.Left)
        assertSame(answer, publisherPc.remoteDesc)
    }

    @Test
    fun applyServerAnswerPassesOfferId() = runTest {
        val answer = answer()

        transport().applyServerAnswer(answer, offerId = 7, midToTrackId = emptyMap())

        verifyBlocking(publisher) { setRemoteDescription(answer, 7) }
    }

    @Test
    fun applyServerAnswerFailsWhenClosed() = runTest {
        publisher.close()

        val result = transport().applyServerAnswer(answer(), offerId = 7, midToTrackId = emptyMap())

        assertTrue("$result", result is Either.Right)
    }

    // endregion

    // region trackIdForReceiver

    /**
     * Adds a receiving media section with [mid] to the publisher.
     *
     * @return the receiver of the media section.
     */
    private fun receiverForMid(mid: String?, mediaType: MediaType = MediaType.MEDIA_TYPE_AUDIO): RtpReceiver {
        val transceiver = publisherPc.addTransceiver(
            mediaType,
            RtpTransceiverInit(RtpTransceiverDirection.RECV_ONLY),
        )
        whenever(transceiver.mid).thenReturn(mid)
        return transceiver.receiver
    }

    @Test
    fun trackIdForReceiverIsNullBeforeAnswer() {
        val receiver = receiverForMid("0")

        assertNull(transport().trackIdForReceiver(receiver))
    }

    @Test
    fun trackIdForReceiverComesFromAnswer() = runTest {
        val transport = transport()
        val audioReceiver = receiverForMid("0")
        val videoReceiver = receiverForMid("1", MediaType.MEDIA_TYPE_VIDEO)

        transport.applyServerAnswer(answer(), offerId = 7, midToTrackId = mapOf("0" to "TR_a", "1" to "TR_b"))

        assertEquals("TR_a", transport.trackIdForReceiver(audioReceiver))
        assertEquals("TR_b", transport.trackIdForReceiver(videoReceiver))
    }

    @Test
    fun trackIdForReceiverIsNullForUnmappedMid() = runTest {
        val transport = transport()
        val receiver = receiverForMid("2")

        transport.applyServerAnswer(answer(), offerId = 7, midToTrackId = mapOf("0" to "TR_a"))

        assertNull(transport.trackIdForReceiver(receiver))
    }

    @Test
    fun trackIdForReceiverIsNullWithoutMid() = runTest {
        val transport = transport()
        val receiver = receiverForMid(null)

        transport.applyServerAnswer(answer(), offerId = 7, midToTrackId = mapOf("0" to "TR_a"))

        assertNull(transport.trackIdForReceiver(receiver))
    }

    @Test
    fun trackIdForReceiverIsNullForUnknownReceiver() = runTest {
        val transport = transport()
        receiverForMid("0")

        transport.applyServerAnswer(answer(), offerId = 7, midToTrackId = mapOf("0" to "TR_a"))

        assertNull(transport.trackIdForReceiver(MockRtpReceiver.create(id = "unknown")))
    }

    @Test
    fun trackIdForReceiverIsNullWhenClosed() = runTest {
        val transport = transport()
        val receiver = receiverForMid("0")
        transport.applyServerAnswer(answer(), offerId = 7, midToTrackId = mapOf("0" to "TR_a"))

        publisher.close()

        assertNull(transport.trackIdForReceiver(receiver))
    }

    @Test
    fun trackIdForReceiverKeepsMappingAcrossAnswerWithoutOne() = runTest {
        val transport = transport()
        val receiver = receiverForMid("0")

        transport.applyServerAnswer(answer("first"), offerId = 7, midToTrackId = mapOf("0" to "TR_a"))
        transport.applyServerAnswer(answer("second"), offerId = 8, midToTrackId = emptyMap())

        assertEquals("TR_a", transport.trackIdForReceiver(receiver))
    }

    @Test
    fun trackIdForReceiverTakesLatestAnswerMapping() = runTest {
        val transport = transport()
        val receiver = receiverForMid("0")

        transport.applyServerAnswer(answer("first"), offerId = 7, midToTrackId = mapOf("0" to "TR_a"))
        transport.applyServerAnswer(answer("second"), offerId = 8, midToTrackId = mapOf("0" to "TR_b"))

        assertEquals("TR_b", transport.trackIdForReceiver(receiver))
    }

    // endregion

    // region handleMediaSectionsRequirement

    private fun requirement(audios: Int, videos: Int) = MediaSectionsRequirement.newBuilder()
        .setNumAudios(audios)
        .setNumVideos(videos)
        .build()

    @Test
    fun handleMediaSectionsRequirementAddsRequiredSections() = runTest {
        transport().handleMediaSectionsRequirement(requirement(audios = 2, videos = 3))

        val mediaTypes = publisherPc.transceivers.map { it.mediaType }
        assertEquals(2, mediaTypes.count { it == MediaType.MEDIA_TYPE_AUDIO })
        assertEquals(3, mediaTypes.count { it == MediaType.MEDIA_TYPE_VIDEO })
    }

    @Test
    fun handleMediaSectionsRequirementAddsReceiveOnlySections() = runTest {
        transport().handleMediaSectionsRequirement(requirement(audios = 2, videos = 3))

        assertTrue(publisherPc.transceivers.isNotEmpty())
        publisherPc.transceivers.forEach { transceiver ->
            assertEquals(RtpTransceiverDirection.RECV_ONLY, transceiver.direction)
        }
    }

    @Test
    fun handleMediaSectionsRequirementWithNoSectionsAddsNothing() = runTest {
        transport().handleMediaSectionsRequirement(requirement(audios = 0, videos = 0))

        assertTrue(publisherPc.transceivers.isEmpty())
    }

    @Test
    fun handleMediaSectionsRequirementWhenClosedAddsNothing() = runTest {
        publisher.close()

        transport().handleMediaSectionsRequirement(requirement(audios = 2, videos = 3))

        assertTrue(publisherPc.transceivers.isEmpty())
    }

    // endregion

    // region resume

    @Test
    fun prepareForIceRestartLeavesPublisherAlone() {
        transport().prepareForIceRestart()

        verify(publisher, never()).prepareForIceRestart()
    }

    @Test
    fun updateRTCConfigAppliesToPublisher() {
        val config = RTCConfiguration(emptyList())

        transport().updateRTCConfig(config)

        assertSame(config, publisherPc.rtcConfig)
    }

    @Test
    fun isConnectedRequiresPublisher() = runTest {
        assertFalse(transport().isConnected(includePublisher = false))
        assertFalse(transport().isConnected(includePublisher = true))
    }

    @Test
    fun isConnectedWhenPublisherConnected() = runTest {
        publisherPc.moveToIceConnectionState(IceConnectionState.CONNECTED)

        assertTrue(transport().isConnected(includePublisher = false))
        assertTrue(transport().isConnected(includePublisher = true))
    }

    @Test
    fun waitUntilConnectedWaitsForPublisher() = runTest {
        val transport = transport()
        val job = launch { transport.waitUntilConnected(includePublisher = true) }

        runCurrent()
        assertTrue(job.isActive)

        publisherState.connectionState = PeerConnectionState.CONNECTED
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun waitUntilConnectedWaitsForPublisherWhenNotIncluded() = runTest {
        val transport = transport()
        val job = launch { transport.waitUntilConnected(includePublisher = false) }

        runCurrent()
        assertTrue(job.isActive)

        publisherState.connectionState = PeerConnectionState.CONNECTED
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun syncStateDescriptionsComeFromPublisher() = runTest {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "publisher_local")
        val answer = SessionDescription(SessionDescription.Type.ANSWER, "publisher_remote")
        publisherPc.localDesc = offer
        publisherPc.remoteDesc = answer

        val descriptions = transport().syncStateDescriptions()

        assertEquals(answer.toProtoSessionDescription(), descriptions.answer)
        assertEquals(offer.toProtoSessionDescription(), descriptions.offer)
    }

    @Test
    fun syncStateDescriptionsWhenPublisherClosed() = runTest {
        publisherPc.localDesc = SessionDescription(SessionDescription.Type.OFFER, "publisher_local")
        publisherPc.remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, "publisher_remote")
        publisher.close()

        val descriptions = transport().syncStateDescriptions()

        assertNull(descriptions.answer)
        assertNull(descriptions.offer)
    }

    @Test
    fun syncStateDescriptionsBeforeNegotiation() = runTest {
        val descriptions = transport().syncStateDescriptions()

        assertNull(descriptions.answer)
        assertNull(descriptions.offer)
    }

    // endregion

    // region close

    @Test
    fun closeClosesPublisher() = runTest {
        transport().close()

        assertTrue(publisher.isClosed())
    }

    // endregion
}
