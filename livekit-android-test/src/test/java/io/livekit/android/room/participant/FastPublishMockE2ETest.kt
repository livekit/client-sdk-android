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

package io.livekit.android.room.participant

import io.livekit.android.ConnectOptions
import io.livekit.android.room.util.SignalUrlBuilder
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.room.track.createMockLocalAudioTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import livekit.LivekitModels
import livekit.LivekitRtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.GZIPInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class FastPublishMockE2ETest : MockE2ETest() {
    @Test fun dualFast() = checkPublication(singlePC = false, fast = true)

    @Test fun dualSequential() = checkPublication(singlePC = false, fast = false)

    @Test fun singleFast() = checkPublication(singlePC = true, fast = true)

    @Test fun singleSequential() = checkPublication(singlePC = true, fast = false)

    private fun checkPublication(singlePC: Boolean, fast: Boolean) = runTest {
        val connecting = launch {
            room.connect(TestData.EXAMPLE_URL, "token", ConnectOptions(singlePeerConnection = singlePC))
        }
        prepareSignal()
        connecting.join()
        if (singlePC) {
            val encoded = wsFactory.request.url.queryParameter(SignalUrlBuilder.QUERY_JOIN_REQUEST)
            assertNotNull(encoded)
            val wrapped = LivekitRtc.WrappedJoinRequest.parseFrom(Base64.getUrlDecoder().decode(encoded))
            val bytes = if (wrapped.compression == LivekitRtc.WrappedJoinRequest.Compression.GZIP) {
                GZIPInputStream(wrapped.joinRequest.newInput()).use { it.readBytes() }
            } else {
                wrapped.joinRequest.toByteArray()
            }
            val join = LivekitRtc.JoinRequest.parseFrom(bytes)
            simulateMessageFromServer(
                LivekitRtc.SignalResponse.newBuilder().setAnswer(
                    LivekitRtc.SessionDescription.newBuilder().setType("answer").setSdp("answer")
                        .setId(join.publisherOffer.id),
                ).build(),
            )
            runCurrent()
        } else {
            connectPeerConnection()
            runCurrent()
        }
        room.localParticipant.setEnabledPublishCodecs(
            if (fast) listOf(LivekitModels.Codec.newBuilder().setMime("video/VP8").build()) else emptyList(),
        )
        var add: LivekitRtc.AddTrackRequest? = null
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasAddTrack()) {
                add = request.addTrack
                // No sender may be created until registration has been sent.
                assertNull(trackUnderTest?.transceiver)
                true
            } else {
                false
            }
        }
        val track = createMockLocalAudioTrack()
        trackUnderTest = track
        val publishing = async { room.localParticipant.publishAudioTrack(track) }
        runCurrent()
        assertNotNull(add)
        assertFalse(publishing.isCompleted)
        assertEquals(fast, track.transceiver != null)
        assertTrue(room.localParticipant.trackPublications.isEmpty())
        simulateMessageFromServer(
            LivekitRtc.SignalResponse.newBuilder().setTrackPublished(
                LivekitRtc.TrackPublishedResponse.newBuilder().setCid(requireNotNull(add).cid)
                    .setTrack(TestData.LOCAL_AUDIO_TRACK),
            ).build(),
        )
        runCurrent()
        assertTrue(publishing.await())
        assertNotNull(track.transceiver)
        assertEquals(1, room.localParticipant.trackPublications.size)
    }

    private var trackUnderTest: io.livekit.android.room.track.LocalAudioTrack? = null
}
