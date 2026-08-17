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

package io.livekit.android.test.mock.room.datatrack

import io.livekit.android.room.datatrack.RemoteDataTrackManagerFactory
import io.livekit.uniffi.RemoteDataTrackManagerDelegate
import io.livekit.uniffi.RemoteDataTrackManagerInterface

class MockRemoteDataTrackManagerFactory : RemoteDataTrackManagerFactory {
    /**
     * The most recently created manager.
     */
    lateinit var manager: MockRemoteDataTrackManager

    override fun create(delegate: RemoteDataTrackManagerDelegate): RemoteDataTrackManagerInterface {
        return MockRemoteDataTrackManager(delegate).also { manager = it }
    }
}

class MockRemoteDataTrackManager(
    val delegate: RemoteDataTrackManagerDelegate,
) : RemoteDataTrackManagerInterface, AutoCloseable {
    val handledJoinResponses = mutableListOf<ByteArray>()
    val handledParticipantUpdates = mutableListOf<ByteArray>()
    val handledSubscriberHandles = mutableListOf<ByteArray>()
    val handledPackets = mutableListOf<ByteArray>()
    var closed = false
        private set

    override fun handlePacketReceived(packet: ByteArray) {
        handledPackets.add(packet)
    }

    override fun handleSfuJoinResponse(res: ByteArray) {
        handledJoinResponses.add(res)
    }

    override fun handleSfuParticipantUpdate(res: ByteArray, localParticipantIdentity: String) {
        handledParticipantUpdates.add(res)
    }

    override fun handleSubscriberHandles(res: ByteArray) {
        handledSubscriberHandles.add(res)
    }

    override fun resendSubscriptionUpdates() {}

    override fun close() {
        closed = true
    }
}
