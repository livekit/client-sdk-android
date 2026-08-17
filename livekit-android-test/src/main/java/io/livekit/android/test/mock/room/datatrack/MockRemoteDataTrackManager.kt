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
import io.livekit.uniffi.DataTrackInfo
import io.livekit.uniffi.DataTrackStream
import io.livekit.uniffi.DataTrackSubscribeOptions
import io.livekit.uniffi.NoHandle
import io.livekit.uniffi.RemoteDataTrack
import io.livekit.uniffi.RemoteDataTrackManagerDelegate
import io.livekit.uniffi.RemoteDataTrackManagerInterface
import uniffi.livekit_datatrack.DecryptionProvider

class MockRemoteDataTrackManagerFactory : RemoteDataTrackManagerFactory {
    /**
     * The most recently created manager.
     */
    lateinit var manager: MockRemoteDataTrackManager

    /**
     * Decryption provider passed into the last [create] call.
     */
    var lastDecryptionProvider: DecryptionProvider? = null
        private set

    override fun create(
        delegate: RemoteDataTrackManagerDelegate,
        decryptionProvider: DecryptionProvider?,
    ): RemoteDataTrackManagerInterface {
        lastDecryptionProvider = decryptionProvider
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
    var resendSubscriptionUpdatesCount = 0
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

    override fun resendSubscriptionUpdates() {
        resendSubscriptionUpdatesCount++
    }

    /**
     * Fires [RemoteDataTrackManagerDelegate.onTrackPublished] as the UniFFI manager would.
     */
    fun simulateTrackPublished(
        name: String,
        publisherIdentity: String,
        sid: String = "DT_mock",
    ): MockFfiRemoteDataTrack {
        val track = MockFfiRemoteDataTrack(
            name = name,
            publisherIdentity = publisherIdentity,
            sid = sid,
        )
        delegate.onTrackPublished(track)
        return track
    }

    /**
     * Fires [RemoteDataTrackManagerDelegate.onTrackUnpublished] as the UniFFI manager would.
     */
    fun simulateTrackUnpublished(sid: String) {
        delegate.onTrackUnpublished(sid)
    }

    override fun close() {
        closed = true
    }
}

/**
 * UniFFI [RemoteDataTrack] stand-in that does not touch native code.
 */
class MockFfiRemoteDataTrack(
    name: String,
    publisherIdentity: String,
    sid: String = "DT_mock",
) : RemoteDataTrack(NoHandle) {
    private val trackInfo = DataTrackInfo(
        sid = sid,
        name = name,
        usesE2ee = false,
        schema = null,
        frameEncoding = null,
    )
    private val identity = publisherIdentity

    override fun info(): DataTrackInfo = trackInfo

    override fun isPublished(): Boolean = true

    override fun publisherIdentity(): String = identity

    override suspend fun subscribe(): DataTrackStream {
        throw UnsupportedOperationException("subscribe is not supported in tests")
    }

    override suspend fun subscribeWithOptions(options: DataTrackSubscribeOptions): DataTrackStream {
        throw UnsupportedOperationException("subscribe is not supported in tests")
    }

    override suspend fun waitForUnpublish() {}
}
