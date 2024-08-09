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

import io.livekit.android.events.RoomEvent
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.assert.assertIsClass
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
