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
import io.livekit.android.mock.MockEglBase
import io.livekit.android.mock.MockVideoCapturer
import io.livekit.android.mock.MockVideoStreamTrack
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.SignalClientTest
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.util.toOkioByteString
import io.livekit.android.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.LivekitRtc.SubscribedCodec
import livekit.LivekitRtc.SubscribedQuality
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.kotlin.argThat
import org.robolectric.RobolectricTestRunner
import livekit.org.webrtc.VideoSource

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class LocalParticipantMockE2ETest : MockE2ETest() {

    @Test
    fun disconnectCleansLocalParticipant() = runTest {
        connect()

        room.localParticipant.publishAudioTrack(
            LocalAudioTrack(
                "",
                MockAudioStreamTrack(id = SignalClientTest.LOCAL_TRACK_PUBLISHED.trackPublished.cid),
            ),
        )

        room.disconnect()

        assertEquals("", room.localParticipant.sid)
        assertNull(room.localParticipant.name)
        assertNull(room.localParticipant.identity)
        assertNull(room.localParticipant.metadata)
        assertNull(room.localParticipant.permissions)
        assertNull(room.localParticipant.participantInfo)
        assertFalse(room.localParticipant.isSpeaking)
        assertEquals(ConnectionQuality.UNKNOWN, room.localParticipant.connectionQuality)

        assertEquals(0, room.localParticipant.trackPublications.values.size)
        assertEquals(0, room.localParticipant.audioTrackPublications.size)
        assertEquals(0, room.localParticipant.videoTrackPublications.size)
    }

    @Test
    fun updateName() = runTest {
        connect()
        val newName = "new_name"
        wsFactory.ws.clearRequests()

        room.localParticipant.updateName(newName)

        val requestString = wsFactory.ws.sentRequests.first().toPBByteString()
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()

        assertTrue(sentRequest.hasUpdateMetadata())
        assertEquals(newName, sentRequest.updateMetadata.name)
    }

    @Test
    fun updateMetadata() = runTest {
        connect()
        val newMetadata = "new_metadata"
        wsFactory.ws.clearRequests()

        room.localParticipant.updateMetadata(newMetadata)

        val requestString = wsFactory.ws.sentRequests.first().toPBByteString()
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()

        assertTrue(sentRequest.hasUpdateMetadata())
        assertEquals(newMetadata, sentRequest.updateMetadata.metadata)
    }

    @Test
    fun participantMetadataChanged() = runTest {
        connect()

        val roomEventsCollector = EventCollector(room.events, coroutineRule.scope)
        val participantEventsCollector = EventCollector(room.localParticipant.events, coroutineRule.scope)

        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.LOCAL_PARTICIPANT_METADATA_CHANGED.toOkioByteString(),
        )

        val roomEvents = roomEventsCollector.stopCollecting()
        val participantEvents = participantEventsCollector.stopCollecting()

        val localParticipant = room.localParticipant
        val updateData = SignalClientTest.REMOTE_PARTICIPANT_METADATA_CHANGED.update.getParticipants(0)
        assertEquals(updateData.metadata, localParticipant.metadata)
        assertEquals(updateData.name, localParticipant.name)

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

    private fun createLocalTrack() = LocalVideoTrack(
        capturer = MockVideoCapturer(),
        source = mock(VideoSource::class.java),
        name = "",
        options = LocalVideoTrackOptions(
            isScreencast = false,
            deviceId = null,
            position = null,
            captureParams = VideoCaptureParameter(width = 0, height = 0, maxFps = 0),
        ),
        rtcTrack = MockVideoStreamTrack(),
        peerConnectionFactory = component.peerConnectionFactory(),
        context = context,
        eglBase = MockEglBase(),
        defaultsManager = DefaultsManager(),
        trackFactory = mock(LocalVideoTrack.Factory::class.java),
    )

    @Test
    fun publishSetCodecPreferencesH264() = runTest {
        room.videoTrackPublishDefaults = room.videoTrackPublishDefaults.copy(videoCodec = "h264")
        connect()

        room.localParticipant.publishVideoTrack(track = createLocalTrack())

        val peerConnection = getPublisherPeerConnection()
        val transceiver = peerConnection.transceivers.first()

        Mockito.verify(transceiver).setCodecPreferences(
            argThat { codecs ->
                val preferredCodec = codecs.first()
                return@argThat preferredCodec.name.lowercase() == "h264" &&
                    preferredCodec.parameters["profile-level-id"] == "42e01f"
            },
        )
    }

    @Test
    fun publishSetCodecPreferencesVP8() = runTest {
        room.videoTrackPublishDefaults = room.videoTrackPublishDefaults.copy(videoCodec = "vp8")
        connect()

        room.localParticipant.publishVideoTrack(track = createLocalTrack())

        val peerConnection = getPublisherPeerConnection()
        val transceiver = peerConnection.transceivers.first()

        Mockito.verify(transceiver).setCodecPreferences(
            argThat { codecs ->
                val preferredCodec = codecs.first()
                return@argThat preferredCodec.name.lowercase() == "vp8"
            },
        )
    }

    @Test
    fun publishSvcCodec() = runTest {
        room.videoTrackPublishDefaults = room.videoTrackPublishDefaults.copy(
            videoCodec = VideoCodec.VP9.codecName,
            scalabilityMode = "L3T3",
            backupCodec = BackupVideoCodec(codec = VideoCodec.VP8.codecName),
        )

        connect()
        wsFactory.ws.clearRequests()
        room.localParticipant.publishVideoTrack(track = createLocalTrack())

        // Expect add track request to contain both primary and backup
        assertEquals(1, wsFactory.ws.sentRequests.size)

        val requestString = wsFactory.ws.sentRequests[0]
        val signalRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString.toPBByteString())
            .build()
        assertTrue(signalRequest.hasAddTrack())

        val addTrackRequest = signalRequest.addTrack
        assertEquals(2, addTrackRequest.simulcastCodecsList.size)

        val vp9Codec = addTrackRequest.simulcastCodecsList[0]
        assertEquals("vp9", vp9Codec.codec)

        val vp8Codec = addTrackRequest.simulcastCodecsList[1]
        assertEquals("vp8", vp8Codec.codec)

        val publisherConn = getPublisherPeerConnection()

        assertEquals(1, publisherConn.transceivers.size)
        Mockito.verify(publisherConn.transceivers.first()).setCodecPreferences(
            argThat { codecs ->
                val preferredCodec = codecs.first()
                return@argThat preferredCodec.name.lowercase() == "vp9"
            },
        )

        // Ensure the newly subscribed vp8 codec gets added as a new transceiver.
        wsFactory.receiveMessage(
            with(LivekitRtc.SignalResponse.newBuilder()) {
                subscribedQualityUpdate = with(LivekitRtc.SubscribedQualityUpdate.newBuilder()) {
                    trackSid = room.localParticipant.videoTrackPublications.first().first.sid
                    addAllSubscribedCodecs(
                        listOf(
                            with(SubscribedCodec.newBuilder()) {
                                codec = "vp9"
                                addAllQualities(
                                    listOf(
                                        SubscribedQuality.newBuilder()
                                            .setQuality(LivekitModels.VideoQuality.HIGH)
                                            .setEnabled(true)
                                            .build(),
                                        SubscribedQuality.newBuilder()
                                            .setQuality(LivekitModels.VideoQuality.MEDIUM)
                                            .setEnabled(true)
                                            .build(),
                                        SubscribedQuality.newBuilder()
                                            .setQuality(LivekitModels.VideoQuality.LOW)
                                            .setEnabled(true)
                                            .build(),
                                    ),
                                )
                                build()
                            },
                            with(SubscribedCodec.newBuilder()) {
                                codec = "vp8"
                                addAllQualities(
                                    listOf(
                                        SubscribedQuality.newBuilder()
                                            .setQuality(LivekitModels.VideoQuality.HIGH)
                                            .setEnabled(true)
                                            .build(),
                                        SubscribedQuality.newBuilder()
                                            .setQuality(LivekitModels.VideoQuality.MEDIUM)
                                            .setEnabled(true)
                                            .build(),
                                        SubscribedQuality.newBuilder()
                                            .setQuality(LivekitModels.VideoQuality.LOW)
                                            .setEnabled(true)
                                            .build(),
                                    ),
                                )
                                build()
                            },
                        ),
                    )
                    build()
                }
                build().toOkioByteString()
            },
        )

        assertEquals(2, publisherConn.transceivers.size)
        Mockito.verify(publisherConn.transceivers.last()).setCodecPreferences(
            argThat { codecs ->
                val preferredCodec = codecs.first()
                return@argThat preferredCodec.name.lowercase() == "vp8"
            },
        )
    }
}
