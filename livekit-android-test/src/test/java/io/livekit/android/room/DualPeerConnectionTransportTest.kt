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
import io.livekit.android.room.util.PeerConnectionStateObservable
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.MockRTCThreadToken
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
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.MockPeerConnectionFactory
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnection.IceConnectionState
import livekit.org.webrtc.PeerConnection.PeerConnectionState
import livekit.org.webrtc.PeerConnection.RTCConfiguration
import livekit.org.webrtc.RtpTransceiver
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
class DualPeerConnectionTransportTest {

    private class FakeConnectionState : PeerConnectionStateObservable {
        @FlowObservable
        @get:FlowObservable
        override var connectionState by flowDelegate(PeerConnectionState.NEW)
    }

    private lateinit var publisher: PeerConnectionTransport
    private lateinit var subscriber: PeerConnectionTransport
    private val publisherState = FakeConnectionState()
    private val subscriberState = FakeConnectionState()

    private val publisherPc: MockPeerConnection
        get() = publisher.peerConnection as MockPeerConnection
    private val subscriberPc: MockPeerConnection
        get() = subscriber.peerConnection as MockPeerConnection

    private val candidate = IceCandidate("0", 0, "candidate:1 1 udp 2122260223 192.168.1.2 50000 typ host")

    @Before
    fun setup() {
        WebRTCInitializer.initialize()
        publisher = createPeerConnectionTransport()
        subscriber = createPeerConnectionTransport()
    }

    private fun createPeerConnectionTransport(): PeerConnectionTransport = spy(
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

    private fun transport(
        subscriberPrimary: Boolean = true,
        fastPublish: Boolean = false,
    ) = DualPeerConnectionTransport(
        publisher = publisher,
        subscriber = subscriber,
        publisherState = publisherState,
        subscriberState = subscriberState,
        subscriberPrimary = subscriberPrimary,
        fastPublish = fastPublish,
    )

    // region roles

    @Test
    fun publisherIsPublishingPeerConnection() {
        assertSame(publisher, transport().publisher)
    }

    @Test
    fun subscriberIsReceivingPeerConnection() {
        assertSame(subscriber, transport().subscriber)
    }

    @Test
    fun publisherConnectsOnDemandWhenSubscriberPrimary() {
        assertTrue(transport(subscriberPrimary = true).publisherConnectsOnDemand)
    }

    @Test
    fun publisherConnectsOnJoinWhenPublisherPrimary() {
        assertFalse(transport(subscriberPrimary = false).publisherConnectsOnDemand)
    }

    @Test
    fun publisherOffersDeclineToReceive() {
        val declineToReceive = mapOf(
            MediaConstraintKeys.OFFER_TO_RECV_AUDIO to MediaConstraintKeys.FALSE,
            MediaConstraintKeys.OFFER_TO_RECV_VIDEO to MediaConstraintKeys.FALSE,
        )

        for (subscriberPrimary in listOf(true, false)) {
            val constraints = transport(subscriberPrimary = subscriberPrimary).publisherOfferConstraints
            assertEquals(declineToReceive, constraints.associate { it.key to it.value })
        }
    }

    // endregion

    // region shouldNegotiatePublisherOnJoin

    @Test
    fun subscriberPrimaryDefersPublisher() {
        assertFalse(transport(subscriberPrimary = true).shouldNegotiatePublisherOnJoin(hasPublished = false))
    }

    @Test
    fun publisherPrimaryNegotiatesPublisherOnJoin() {
        assertTrue(transport(subscriberPrimary = false).shouldNegotiatePublisherOnJoin(hasPublished = false))
    }

    @Test
    fun fastPublishNegotiatesPublisherOnJoin() {
        assertTrue(
            transport(subscriberPrimary = true, fastPublish = true)
                .shouldNegotiatePublisherOnJoin(hasPublished = false),
        )
    }

    @Test
    fun hasPublishedNegotiatesPublisherOnJoin() {
        assertTrue(transport(subscriberPrimary = true).shouldNegotiatePublisherOnJoin(hasPublished = true))
    }

    @Test
    fun shouldNegotiatePublisherOnJoinDoesNotNegotiate() {
        val transport = transport(subscriberPrimary = false)

        transport.shouldNegotiatePublisherOnJoin(hasPublished = true)

        verify(publisher, never()).negotiate
        verify(subscriber, never()).negotiate
    }

    // endregion

    // region addIceCandidate

    @Test
    fun publisherCandidateGoesToPublisher() {
        assertTrue(transport().addIceCandidate(candidate, SignalTarget.PUBLISHER))

        verify(publisher).addIceCandidate(candidate)
        verify(subscriber, never()).addIceCandidate(any())
    }

    @Test
    fun subscriberCandidateGoesToSubscriber() {
        assertTrue(transport().addIceCandidate(candidate, SignalTarget.SUBSCRIBER))

        verify(subscriber).addIceCandidate(candidate)
        verify(publisher, never()).addIceCandidate(any())
    }

    @Test
    fun unrecognizedTargetCandidateIsRejected() {
        assertFalse(transport().addIceCandidate(candidate, SignalTarget.UNRECOGNIZED))

        verify(publisher, never()).addIceCandidate(any())
        verify(subscriber, never()).addIceCandidate(any())
    }

    // endregion

    // region answerServerOffer

    @Test
    fun answerServerOfferAnswersOnSubscriber() = runTest {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "remote_offer")

        val result = transport().answerServerOffer(offer, offerId = 7)

        assertTrue("$result", result is Either.Left)
        val answer = (result as Either.Left).value
        assertEquals(SessionDescription.Type.ANSWER, answer.type)
        assertSame(offer, subscriberPc.remoteDesc)
        assertSame(answer, subscriberPc.localDesc)
    }

    @Test
    fun answerServerOfferPassesOfferId() = runTest {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "remote_offer")

        transport().answerServerOffer(offer, offerId = 7)

        verifyBlocking(subscriber) { setRemoteDescription(offer, 7) }
    }

    @Test
    fun answerServerOfferLeavesPublisherAlone() = runTest {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "remote_offer")

        transport().answerServerOffer(offer, offerId = 7)

        assertNull(publisherPc.remoteDesc)
        assertNull(publisherPc.localDesc)
    }

    @Test
    fun answerServerOfferFailsOnInvalidOffer() = runTest {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "")

        val result = transport().answerServerOffer(offer, offerId = 7)

        assertTrue("$result", result is Either.Right)
        assertNull(subscriberPc.localDesc)
    }

    @Test
    fun answerServerOfferFailsWhenClosed() = runTest {
        subscriber.close()
        val offer = SessionDescription(SessionDescription.Type.OFFER, "remote_offer")

        val result = transport().answerServerOffer(offer, offerId = 7)

        assertTrue("$result", result is Either.Right)
    }

    // endregion

    // region applyServerAnswer

    @Test
    fun applyServerAnswerAppliesToPublisher() = runTest {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, "remote_answer")

        val result = transport().applyServerAnswer(answer, offerId = 7, midToTrackId = emptyMap())

        assertTrue("$result", result is Either.Left)
        assertSame(answer, publisherPc.remoteDesc)
    }

    @Test
    fun applyServerAnswerPassesOfferId() = runTest {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, "remote_answer")

        transport().applyServerAnswer(answer, offerId = 7, midToTrackId = emptyMap())

        verifyBlocking(publisher) { setRemoteDescription(answer, 7) }
    }

    @Test
    fun applyServerAnswerLeavesSubscriberAlone() = runTest {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, "remote_answer")

        transport().applyServerAnswer(answer, offerId = 7, midToTrackId = emptyMap())

        assertNull(subscriberPc.remoteDesc)
        assertNull(subscriberPc.localDesc)
    }

    @Test
    fun applyServerAnswerFailsWhenClosed() = runTest {
        publisher.close()
        val answer = SessionDescription(SessionDescription.Type.ANSWER, "remote_answer")

        val result = transport().applyServerAnswer(answer, offerId = 7, midToTrackId = emptyMap())

        assertTrue("$result", result is Either.Right)
    }

    // endregion

    // region trackIdForReceiver

    @Test
    fun trackIdForReceiverIgnoresAnswerMapping() = runTest {
        val transport = transport()
        val transceiver = subscriberPc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        whenever(transceiver.mid).thenReturn("0")
        val answer = SessionDescription(SessionDescription.Type.ANSWER, "remote_answer")

        transport.applyServerAnswer(answer, offerId = 7, midToTrackId = mapOf("0" to "TR_remote"))

        assertNull(transport.trackIdForReceiver(transceiver.receiver))
    }

    // endregion

    // region handleMediaSectionsRequirement

    private val mediaSectionsRequirement = MediaSectionsRequirement.newBuilder()
        .setNumAudios(2)
        .setNumVideos(3)
        .build()

    @Test
    fun handleMediaSectionsRequirementAddsNoTransceivers() = runTest {
        transport().handleMediaSectionsRequirement(mediaSectionsRequirement)

        assertTrue(publisherPc.transceivers.isEmpty())
        assertTrue(subscriberPc.transceivers.isEmpty())
    }

    @Test
    fun handleMediaSectionsRequirementDoesNotNegotiate() = runTest {
        transport().handleMediaSectionsRequirement(mediaSectionsRequirement)

        verify(publisher, never()).negotiate
        assertNull(publisherPc.localDesc)
    }

    // endregion

    // region resume

    @Test
    fun prepareForIceRestartPreparesSubscriber() {
        transport().prepareForIceRestart()

        verify(subscriber).prepareForIceRestart()
        verify(publisher, never()).prepareForIceRestart()
    }

    @Test
    fun updateRTCConfigAppliesToBoth() {
        val config = RTCConfiguration(emptyList())

        transport().updateRTCConfig(config)

        assertSame(config, publisherPc.rtcConfig)
        assertSame(config, subscriberPc.rtcConfig)
    }

    @Test
    fun isConnectedRequiresSubscriber() = runTest {
        publisherPc.moveToIceConnectionState(IceConnectionState.CONNECTED)

        assertFalse(transport().isConnected(includePublisher = false))
        assertFalse(transport().isConnected(includePublisher = true))
    }

    @Test
    fun isConnectedIgnoresPublisherWhenNotIncluded() = runTest {
        subscriberPc.moveToIceConnectionState(IceConnectionState.CONNECTED)

        assertTrue(transport().isConnected(includePublisher = false))
    }

    @Test
    fun isConnectedRequiresPublisherWhenIncluded() = runTest {
        subscriberPc.moveToIceConnectionState(IceConnectionState.CONNECTED)

        assertFalse(transport().isConnected(includePublisher = true))

        publisherPc.moveToIceConnectionState(IceConnectionState.CONNECTED)

        assertTrue(transport().isConnected(includePublisher = true))
    }

    @Test
    fun waitUntilConnectedWaitsForSubscriber() = runTest {
        val transport = transport()
        val job = launch { transport.waitUntilConnected(includePublisher = false) }

        runCurrent()
        assertTrue(job.isActive)

        subscriberState.connectionState = PeerConnectionState.CONNECTED
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun waitUntilConnectedIgnoresPublisherWhenNotIncluded() = runTest {
        subscriberState.connectionState = PeerConnectionState.CONNECTED
        val transport = transport()
        val job = launch { transport.waitUntilConnected(includePublisher = false) }

        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun waitUntilConnectedWaitsForPublisherWhenIncluded() = runTest {
        subscriberState.connectionState = PeerConnectionState.CONNECTED
        val transport = transport()
        val job = launch { transport.waitUntilConnected(includePublisher = true) }

        runCurrent()
        assertTrue(job.isActive)

        publisherState.connectionState = PeerConnectionState.CONNECTED
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun waitUntilConnectedWaitsForBothAtOnce() = runTest {
        val transport = transport()
        val job = launch { transport.waitUntilConnected(includePublisher = true) }
        runCurrent()

        subscriberState.connectionState = PeerConnectionState.CONNECTED
        runCurrent()
        subscriberState.connectionState = PeerConnectionState.DISCONNECTED
        publisherState.connectionState = PeerConnectionState.CONNECTED
        runCurrent()

        assertTrue(job.isCompleted)
    }

    @Test
    fun syncStateDescriptionsComeFromSubscriber() = runTest {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "subscriber_remote")
        val answer = SessionDescription(SessionDescription.Type.ANSWER, "subscriber_local")
        subscriberPc.remoteDesc = offer
        subscriberPc.localDesc = answer
        publisherPc.localDesc = SessionDescription(SessionDescription.Type.OFFER, "publisher_local")
        publisherPc.remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, "publisher_remote")

        val descriptions = transport().syncStateDescriptions()

        assertEquals(answer.toProtoSessionDescription(), descriptions.answer)
        assertEquals(offer.toProtoSessionDescription(), descriptions.offer)
    }

    @Test
    fun syncStateDescriptionsWhenSubscriberClosed() = runTest {
        subscriberPc.remoteDesc = SessionDescription(SessionDescription.Type.OFFER, "subscriber_remote")
        subscriberPc.localDesc = SessionDescription(SessionDescription.Type.ANSWER, "subscriber_local")
        subscriber.close()

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
    fun closeClosesBoth() = runTest {
        transport().close()

        assertTrue(publisher.isClosed())
        assertTrue(subscriber.isClosed())
    }

    // endregion
}
