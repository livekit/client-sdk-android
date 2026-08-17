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

package io.livekit.android.room.datatrack

import io.livekit.android.room.Room
import io.livekit.android.test.MockE2ETest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTrackManagerMockE2ETest : MockE2ETest() {

    @Test
    fun connectForwardsJoinToInjectedRemoteManager() = runTest {
        connect()

        assertEquals(Room.State.CONNECTED, room.state)
        val remote = remoteDataTrackManagerFactory.manager
        assertTrue(remote.handledJoinResponses.isNotEmpty())
    }

    @Test
    fun publishDataTrackUsesInjectedLocalManager() = runTest {
        connect()

        val result = room.localParticipant.publishDataTrack("telemetry")
        assertTrue(result.isSuccess)
        assertEquals("telemetry", result.getOrThrow().info.name)

        val local = localDataTrackManagerFactory.manager
        assertEquals(1, local.publishedTracks.size)
        assertEquals("telemetry", local.publishedTracks.single().info().name)
    }
}
