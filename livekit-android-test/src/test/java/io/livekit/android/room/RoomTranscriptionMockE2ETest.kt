/*
 * Copyright 2023-2024 LiveKit, Inc.
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

import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.TrackPublicationEvent
import io.livekit.android.room.participant.AudioTrackPublishOptions
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.assert.assertIsClass
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.MockAudioProcessingController
import io.livekit.android.test.mock.MockAudioStreamTrack
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.util.toDataChannelBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomTranscriptionMockE2ETest : MockE2ETest() {
    @Test
    fun transcriptionReceived() = runTest {
        connect()
        room.localParticipant.publishAudioTrack(
            LocalAudioTrack(
                name = "",
                mediaTrack = MockAudioStreamTrack(id = TestData.LOCAL_TRACK_PUBLISHED.trackPublished.cid),
                options = LocalAudioTrackOptions(),
                audioProcessingController = MockAudioProcessingController(),
                dispatcher = coroutineRule.dispatcher,
            ),
            options = AudioTrackPublishOptions(
                source = Track.Source.MICROPHONE,
            ),
        )
        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        val subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)

        val roomCollector = EventCollector(room.events, coroutineRule.scope)
        val participantCollector = EventCollector(room.localParticipant.events, coroutineRule.scope)
        val publicationCollector = EventCollector(room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)!!.events, coroutineRule.scope)

        val dataBuffer = TestData.DATA_PACKET_TRANSCRIPTION.toDataChannelBuffer()

        subDataChannel.observer?.onMessage(dataBuffer)

        val roomEvents = roomCollector.stopCollecting()
        val participantEvents = participantCollector.stopCollecting()
        val publicationEvents = publicationCollector.stopCollecting()

        // Verify room events
        run {
            assertEquals(1, roomEvents.size)
            assertIsClass(RoomEvent.TranscriptionReceived::class.java, roomEvents[0])

            val event = roomEvents.first() as RoomEvent.TranscriptionReceived
            assertEquals(room, event.room)
            assertEquals(room.localParticipant, event.participant)
            assertEquals(room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)!!, event.publication)

            val expectedSegment = TestData.DATA_PACKET_TRANSCRIPTION.transcription.getSegments(0)
            val receivedSegment = event.transcriptionSegments.first()
            assertEquals(expectedSegment.id, receivedSegment.id)
            assertEquals(expectedSegment.text, receivedSegment.text)
        }

        // Verify participant events
        run {
            assertEquals(1, participantEvents.size)
            assertIsClass(ParticipantEvent.TranscriptionReceived::class.java, participantEvents[0])
        }

        // Verify publication events
        run {
            assertEquals(1, publicationEvents.size)
            assertIsClass(TrackPublicationEvent.TranscriptionReceived::class.java, publicationEvents[0])
        }
    }
}
