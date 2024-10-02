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
import io.livekit.android.room.participant.AudioTrackPublishOptions
import io.livekit.android.room.track.Track
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.assert.assertIsClass
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.room.track.createMockLocalAudioTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitRtc
import livekit.LivekitRtc.ParticipantUpdate
import livekit.LivekitRtc.SignalResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomParticipantEventMockE2ETest : MockE2ETest() {

    @Test
    fun localParticipantAttributesChangedEvent() = runTest {
        connect()
        wsFactory.ws.clearRequests()
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasUpdateMetadata()) {
                val newInfo = with(TestData.LOCAL_PARTICIPANT.toBuilder()) {
                    putAllAttributes(request.updateMetadata.attributesMap)
                    build()
                }

                val response = with(SignalResponse.newBuilder()) {
                    update = with(ParticipantUpdate.newBuilder()) {
                        addParticipants(newInfo)
                        build()
                    }
                    build()
                }
                wsFactory.receiveMessage(response)
                return@registerSignalRequestHandler true
            }
            return@registerSignalRequestHandler false
        }

        val newAttributes = mapOf("attribute" to "changedValue")

        val collector = EventCollector(room.events, coroutineRule.scope)
        room.localParticipant.updateAttributes(newAttributes)

        val events = collector.stopCollecting()

        assertEquals(1, events.size)
        assertIsClass(RoomEvent.ParticipantAttributesChanged::class.java, events.first())
    }

    @Test
    fun localTrackSubscribed() = runTest {
        connect()
        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(),
            options = AudioTrackPublishOptions(
                source = Track.Source.MICROPHONE,
            ),
        )
        val roomCollector = EventCollector(room.events, coroutineRule.scope)
        val participantCollector = EventCollector(room.localParticipant.events, coroutineRule.scope)

        wsFactory.receiveMessage(
            with(SignalResponse.newBuilder()) {
                trackSubscribed = with(LivekitRtc.TrackSubscribed.newBuilder()) {
                    trackSid = TestData.LOCAL_AUDIO_TRACK.sid
                    build()
                }
                build()
            },
        )

        val roomEvents = roomCollector.stopCollecting()
        val participantEvents = participantCollector.stopCollecting()

        // Verify room events
        run {
            assertEquals(1, roomEvents.size)
            assertIsClass(RoomEvent.LocalTrackSubscribed::class.java, roomEvents[0])

            val event = roomEvents.first() as RoomEvent.LocalTrackSubscribed
            assertEquals(room, event.room)
            assertEquals(room.localParticipant, event.participant)
            assertEquals(room.localParticipant.getTrackPublication(Track.Source.MICROPHONE), event.publication)
        }

        // Verify participant events
        run {
            assertEquals(1, participantEvents.size)
            assertIsClass(ParticipantEvent.LocalTrackSubscribed::class.java, participantEvents[0])
        }
    }
}
