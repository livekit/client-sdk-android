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

import io.livekit.android.room.datatrack.LocalDataTrackManagerFactory
import io.livekit.uniffi.DataTrackFrame
import io.livekit.uniffi.DataTrackInfo
import io.livekit.uniffi.DataTrackOptions
import io.livekit.uniffi.LocalDataTrack
import io.livekit.uniffi.LocalDataTrackManagerDelegate
import io.livekit.uniffi.LocalDataTrackManagerInterface
import io.livekit.uniffi.NoHandle
import livekit.LivekitModels
import livekit.LivekitRtc

class MockLocalDataTrackManagerFactory : LocalDataTrackManagerFactory {
    /**
     * The most recently created manager.
     */
    lateinit var manager: MockLocalDataTrackManager

    override fun create(delegate: LocalDataTrackManagerDelegate): LocalDataTrackManagerInterface {
        return MockLocalDataTrackManager(delegate).also { manager = it }
    }
}

class MockLocalDataTrackManager(
    val delegate: LocalDataTrackManagerDelegate,
) : LocalDataTrackManagerInterface, AutoCloseable {
    val publishedTracks = mutableListOf<MockFfiLocalDataTrack>()
    val handledPublishResponses = mutableListOf<ByteArray>()
    val handledRequestResponses = mutableListOf<ByteArray>()
    var closed = false
        private set

    override fun handleSfuPublishResponse(res: ByteArray) {
        handledPublishResponses.add(res)
    }

    override fun handleSfuRequestResponse(res: ByteArray) {
        handledRequestResponses.add(res)
    }

    override suspend fun publishResponsesForSyncState(): List<ByteArray> {
        return publishedTracks.filter { it.isPublished() }.map { track ->
            LivekitRtc.PublishDataTrackResponse.newBuilder()
                .setInfo(
                    LivekitModels.DataTrackInfo.newBuilder()
                        .setSid(track.info().sid)
                        .setName(track.info().name)
                        .build(),
                )
                .build()
                .toByteArray()
        }
    }

    override suspend fun publishTrack(options: DataTrackOptions): LocalDataTrack {
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setPublishDataTrackRequest(
                LivekitRtc.PublishDataTrackRequest.newBuilder()
                    .setName(options.name)
                    .build(),
            )
            .build()
            .toByteArray()
        delegate.onSignalRequest(request)
        return MockFfiLocalDataTrack(name = options.name).also { publishedTracks.add(it) }
    }

    var republishTracksCount = 0
        private set

    override fun republishTracks() {
        republishTracksCount++
    }

    override fun close() {
        closed = true
        publishedTracks.forEach { it.unpublish() }
    }
}

/**
 * UniFFI [LocalDataTrack] stand-in that does not touch native code.
 */
class MockFfiLocalDataTrack(
    name: String,
    sid: String = "DT_mock",
) : LocalDataTrack(NoHandle) {
    private var published = true
    private val trackInfo = DataTrackInfo(
        sid = sid,
        name = name,
        usesE2ee = false,
        schema = null,
        frameEncoding = null,
    )
    val pushedFrames = mutableListOf<DataTrackFrame>()

    override fun info(): DataTrackInfo = trackInfo

    override fun isPublished(): Boolean = published

    override fun tryPush(frame: DataTrackFrame) {
        pushedFrames.add(frame)
    }

    override fun unpublish() {
        published = false
    }

    override suspend fun waitForUnpublish() {}
}
