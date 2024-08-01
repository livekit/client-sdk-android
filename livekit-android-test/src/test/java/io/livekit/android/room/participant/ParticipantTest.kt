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

package io.livekit.android.room.participant

import io.livekit.android.events.ParticipantEvent
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.test.coroutines.TestCoroutineRule
import io.livekit.android.test.events.EventCollector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import livekit.LivekitModels
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Date
import kotlin.math.abs

@ExperimentalCoroutinesApi
class ParticipantTest {

    @get:Rule
    var coroutineRule = TestCoroutineRule()

    lateinit var participant: Participant

    @Before
    fun setup() {
        participant = Participant(Participant.Sid(""), null, coroutineRule.dispatcher)
    }

    @Test
    fun updateFromInfo() = runTest {
        participant.updateFromInfo(INFO)

        assertTrue(participant.hasInfo)
        assertEquals(INFO.sid, participant.sid.value)
        assertEquals(INFO.identity, participant.identity?.value)
        assertEquals(INFO.metadata, participant.metadata)
        assertEquals(INFO.name, participant.name)
        assertEquals(Participant.Kind.fromProto(INFO.kind), participant.kind)
        assertEquals(INFO.attributesMap, participant.attributes)

        assertEquals(INFO, participant.participantInfo)
    }

    @Test
    fun setMetadataCallsListeners() = runTest {
        class MetadataListener : ParticipantListener {
            var wasCalled = false
            lateinit var participantValue: Participant
            var prevMetadataValue: String? = null
            override fun onMetadataChanged(participant: Participant, prevMetadata: String?) {
                wasCalled = true
                participantValue = participant
                prevMetadataValue = prevMetadata
            }
        }

        val internalListener = MetadataListener()

        participant.internalListener = internalListener

        val prevMetadata = participant.metadata
        val metadata = "metadata"
        participant.metadata = metadata

        fun checkValues(listener: MetadataListener) {
            assertTrue(listener.wasCalled)
            assertEquals(participant, listener.participantValue)
            assertEquals(prevMetadata, listener.prevMetadataValue)
        }

        checkValues(internalListener)
    }

    @Test
    fun setMetadataChangedEvent() = runTest {
        val eventCollector = EventCollector(participant.events, coroutineRule.scope)
        val prevMetadata = participant.metadata
        val metadata = "metadata"
        participant.metadata = metadata

        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is ParticipantEvent.MetadataChanged)

        val event = events[0] as ParticipantEvent.MetadataChanged

        assertEquals(prevMetadata, event.prevMetadata)
        assertEquals(participant, event.participant)
    }

    @Test
    fun setAttributesChangedEvent() = runTest {
        participant.attributes = INFO.attributesMap

        val eventCollector = EventCollector(participant.events, coroutineRule.scope)
        val oldAttributes = participant.attributes

        val newAttributes = mapOf("newAttribute" to "newValue")
        participant.attributes = newAttributes

        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is ParticipantEvent.AttributesChanged)

        val event = events[0] as ParticipantEvent.AttributesChanged

        val expectedDiff = mapOf("attribute" to "", "newAttribute" to "newValue")
        assertEquals(expectedDiff, event.changedAttributes)
        assertEquals(oldAttributes, event.oldAttributes)
        assertEquals(participant, event.participant)
    }

    @Test
    fun setIsSpeakingChangedEvent() = runTest {
        val eventCollector = EventCollector(participant.events, coroutineRule.scope)
        val newIsSpeaking = !participant.isSpeaking
        participant.isSpeaking = newIsSpeaking

        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is ParticipantEvent.SpeakingChanged)

        val event = events[0] as ParticipantEvent.SpeakingChanged

        assertEquals(participant, event.participant)
        assertEquals(newIsSpeaking, event.isSpeaking)
    }

    @Test
    fun addTrackPublication() = runTest {
        val audioPublication = TrackPublication(TRACK_INFO, null, participant)
        participant.addTrackPublication(audioPublication)

        assertEquals(1, participant.trackPublications.values.size)
        assertEquals(audioPublication, participant.trackPublications.values.first())
        assertEquals(1, participant.audioTrackPublications.size)
        assertEquals(audioPublication, participant.audioTrackPublications.first().first)
    }

    @Test
    fun dispose() = runTest {
        val audioPublication = TrackPublication(TRACK_INFO, null, participant)
        participant.addTrackPublication(audioPublication)

        participant.dispose()
        assertEquals("", participant.sid.value)
        assertNull(participant.name)
        assertNull(participant.identity)
        assertNull(participant.metadata)
        assertNull(participant.permissions)
        assertNull(participant.participantInfo)
        Assert.assertFalse(participant.isSpeaking)
        assertEquals(ConnectionQuality.UNKNOWN, participant.connectionQuality)
    }

    @Test
    fun speakingUpdatesLastSpokeAt() = runTest {
        assertNull(participant.lastSpokeAt)

        participant.isSpeaking = true

        val lastSpokeAt = participant.lastSpokeAt
        val timestamp = Date().time
        assertNotNull(lastSpokeAt)
        assertTrue(abs(lastSpokeAt!! - timestamp) < 1000)
    }

    companion object {
        val INFO = LivekitModels.ParticipantInfo.newBuilder()
            .setSid("sid")
            .setIdentity("identity")
            .setMetadata("metadata")
            .setName("name")
            .setKind(LivekitModels.ParticipantInfo.Kind.STANDARD)
            .putAttributes("attribute", "value")
            .build()

        val TRACK_INFO = LivekitModels.TrackInfo.newBuilder()
            .setSid("sid")
            .setName("name")
            .setType(LivekitModels.TrackType.AUDIO)
            .setMuted(false)
            .setMimeType("audio/mpeg")
            .build()
    }
}
