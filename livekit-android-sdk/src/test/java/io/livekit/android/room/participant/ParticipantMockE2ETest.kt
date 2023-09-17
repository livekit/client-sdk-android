/*
 * Copyright 2023 LiveKit, Inc.
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

package io.livekit.android.room.participant

import io.livekit.android.MockE2ETest
import io.livekit.android.assert.assertIsClassList
import io.livekit.android.events.EventCollector
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.mock.MockAudioStreamTrack
import io.livekit.android.room.SignalClientTest
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class ParticipantMockE2ETest : MockE2ETest() {

    @Test
    fun trackUnpublished() = runTest {
        connect()

        // publish track
        room.localParticipant.publishAudioTrack(
            LocalAudioTrack(
                "",
                MockAudioStreamTrack(id = SignalClientTest.LOCAL_TRACK_PUBLISHED.trackPublished.cid),
            ),
        )

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        // remote unpublish
        simulateMessageFromServer(SignalClientTest.LOCAL_TRACK_UNPUBLISHED)
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.TrackUnpublished)
        assertEquals(0, room.localParticipant.tracks.size)
    }

    @Test
    fun participantPermissions() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        simulateMessageFromServer(SignalClientTest.PERMISSION_CHANGE)
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.ParticipantPermissionsChanged)
    }

    @Test
    fun participantMetadataChanged() = runTest {
        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.PARTICIPANT_JOIN.toOkioByteString(),
        )

        val remoteParticipant = room.remoteParticipants.values.first()
        val roomEventsCollector = EventCollector(room.events, coroutineRule.scope)
        val participantEventsCollector = EventCollector(remoteParticipant.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.REMOTE_PARTICIPANT_METADATA_CHANGED.toOkioByteString(),
        )
        val roomEvents = roomEventsCollector.stopCollecting()
        val participantEvents = participantEventsCollector.stopCollecting()

        val updateData = SignalClientTest.REMOTE_PARTICIPANT_METADATA_CHANGED.update.getParticipants(0)
        assertEquals(updateData.metadata, remoteParticipant.metadata)
        assertEquals(updateData.name, remoteParticipant.name)

        assertIsClassList(
            listOf(
                RoomEvent.ParticipantMetadataChanged::class.java,
                RoomEvent.ParticipantNameChanged::class.java,
            ),
            roomEvents,
        )

        assertIsClassList(
            listOf(
                ParticipantEvent.MetadataChanged::class.java,
                ParticipantEvent.NameChanged::class.java,
            ),
            participantEvents,
        )
    }
}
