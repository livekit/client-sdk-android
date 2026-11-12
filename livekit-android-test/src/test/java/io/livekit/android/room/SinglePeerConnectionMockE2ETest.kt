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

import io.livekit.android.ConnectOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.Track
import io.livekit.android.room.util.SignalUrlBuilder
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.MockAudioStreamTrack
import io.livekit.android.test.mock.MockMediaStream
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.createMediaStreamId
import io.livekit.android.test.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import livekit.LivekitRtc
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RtpTransceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Connecting with [ConnectOptions.singlePeerConnection] to a server that serves `/rtc/v1`:
 * one peer connection carries the join-time offer, the server's media sections, and the
 * received tracks.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class SinglePeerConnectionMockE2ETest : MockE2ETest() {

    private fun publisherPeerConnection() = getPublisherPeerConnection()

    /**
     * @return the most recent offer the client sent to the server.
     */
    private fun lastSentOffer(): LivekitRtc.SessionDescription {
        return wsFactory.ws.sentRequests
            .map { LivekitRtc.SignalRequest.newBuilder().mergeFrom(it.toPBByteString()).build() }
            .last { it.hasOffer() }
            .offer
    }

    /**
     * Answers the client's most recent offer, with [midToTrackId] as the answer's mapping of
     * receiving media sections to track IDs.
     */
    private fun TestScope.answerLastOffer(midToTrackId: Map<String, String> = emptyMap()) {
        val offer = lastSentOffer()
        val answer = with(LivekitRtc.SignalResponse.newBuilder()) {
            setAnswer(
                with(LivekitRtc.SessionDescription.newBuilder()) {
                    type = "answer"
                    sdp = "remote_answer"
                    id = offer.id
                    putAllMidToTrackId(midToTrackId)
                    build()
                },
            )
            build()
        }
        simulateMessageFromServer(answer)
        advanceUntilIdle()
    }

    /**
     * Joins through `/rtc/v1`, answers the offer the join triggers, and connects the peer
     * connection.
     */
    private suspend fun TestScope.connectSinglePeerConnection() {
        val connectJob = coroutineRule.scope.launch {
            room.connect(
                url = TestData.EXAMPLE_URL,
                token = "token",
                options = ConnectOptions(singlePeerConnection = true),
            )
        }
        prepareSignal()
        connectJob.join()
        advanceUntilIdle()

        answerLastOffer()
        publisherPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        runCurrent()
    }

    /**
     * Has the server require [audios] receiving audio sections and [videos] video ones, which
     * leaves the renegotiation offer that follows unanswered.
     */
    private fun TestScope.requireMediaSections(audios: Int, videos: Int) {
        val requirement = with(LivekitRtc.SignalResponse.newBuilder()) {
            setMediaSectionsRequirement(
                with(LivekitRtc.MediaSectionsRequirement.newBuilder()) {
                    numAudios = audios
                    numVideos = videos
                    build()
                },
            )
            build()
        }
        simulateMessageFromServer(requirement)
        advanceUntilIdle()
        // The mock peer connection does not ask for renegotiation on its own.
        publisherPeerConnection().observer?.onRenegotiationNeeded()
        advanceUntilIdle()
    }

    @Test
    fun joinsThroughV1WithOnePeerConnection() = runTest {
        val collector = EventCollector(room.events, coroutineRule.scope)

        connectSinglePeerConnection()

        val request = wsFactory.request
        assertEquals("/rtc/v1", request.url.encodedPath)
        assertNotNull(request.url.queryParameter(SignalUrlBuilder.QUERY_JOIN_REQUEST))
        assertTrue(component.rtcEngine().transport is SinglePeerConnectionTransport)
        assertSame(getPublisherPeerConnection(), getSubscriberPeerConnection())
        assertEquals(Room.State.CONNECTED, room.state)
        val events = collector.stopCollecting()
        assertTrue(events.none { it is RoomEvent.FailedToConnect })
    }

    @Test
    fun publisherOffersOnJoinWithoutOfferingToReceive() = runTest {
        connectSinglePeerConnection()

        val constraints = publisherPeerConnection().lastOfferConstraints
        assertNotNull(constraints)
        assertTrue(constraints!!.mandatory.isEmpty())
    }

    @Test
    fun mediaSectionsRequirementAddsReceivingSections() = runTest {
        connectSinglePeerConnection()

        requireMediaSections(audios = 1, videos = 2)
        answerLastOffer()

        val transceivers = publisherPeerConnection().transceivers
        assertEquals(3, transceivers.size)
        assertTrue(transceivers.all { it.direction == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY })
        assertEquals(1, transceivers.count { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO })
        assertEquals(2, transceivers.count { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO })
    }

    /**
     * Adds one receiving audio section with mid "0" and a track on its receiver, and leaves
     * the renegotiation offer unanswered.
     */
    private fun TestScope.addReceivingAudioSection(): RtpTransceiver {
        requireMediaSections(audios = 1, videos = 0)
        val transceiver = publisherPeerConnection().transceivers.single()
        whenever(transceiver.mid).thenReturn("0")
        whenever(transceiver.receiver.track()).thenReturn(MockAudioStreamTrack())
        return transceiver
    }

    @Test
    fun boundTrackAttachesWithoutTrackCallback() = runTest {
        connectSinglePeerConnection()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        val remoteParticipant = room.getParticipantBySid(TestData.REMOTE_PARTICIPANT.sid) as RemoteParticipant
        addReceivingAudioSection()
        val collector = EventCollector(room.events, coroutineRule.scope)

        answerLastOffer(midToTrackId = mapOf("0" to TestData.REMOTE_AUDIO_TRACK.sid))

        val events = collector.stopCollecting()
        assertEquals(1, events.count { it is RoomEvent.TrackSubscribed })
        val micPub = remoteParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(micPub?.track)
    }

    @Test
    fun trackCallbackAfterBoundTrackDoesNotAttachTwice() = runTest {
        connectSinglePeerConnection()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        val transceiver = addReceivingAudioSection()
        val collector = EventCollector(room.events, coroutineRule.scope)
        answerLastOffer(midToTrackId = mapOf("0" to TestData.REMOTE_AUDIO_TRACK.sid))

        publisherPeerConnection().observer?.onAddTrack(
            transceiver.receiver,
            arrayOf(MockMediaStream(id = createMediaStreamId(TestData.REMOTE_PARTICIPANT.sid, "stream"))),
        )
        advanceUntilIdle()

        val events = collector.stopCollecting()
        assertEquals(1, events.count { it is RoomEvent.TrackSubscribed })
    }

    @Test
    fun serverOfferIsIgnored() = runTest {
        connectSinglePeerConnection()
        val sentBefore = wsFactory.ws.sentRequests.size

        simulateMessageFromServer(TestData.OFFER)
        advanceUntilIdle()

        val answers = wsFactory.ws.sentRequests.drop(sentBefore)
            .map { LivekitRtc.SignalRequest.newBuilder().mergeFrom(it.toPBByteString()).build() }
            .count { it.hasAnswer() }
        assertEquals(0, answers)
        assertEquals(Room.State.CONNECTED, room.state)
    }
}
