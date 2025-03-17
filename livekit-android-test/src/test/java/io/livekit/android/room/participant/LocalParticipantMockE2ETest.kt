/*
 * Copyright 2023-2025 LiveKit, Inc.
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

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackException
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.assert.assertIsClassList
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.mock.MockAudioProcessingController
import io.livekit.android.test.mock.MockEglBase
import io.livekit.android.test.mock.MockVideoCapturer
import io.livekit.android.test.mock.MockVideoStreamTrack
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.camera.MockCameraProvider
import io.livekit.android.test.mock.room.track.createMockLocalAudioTrack
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.LivekitModels
import livekit.LivekitModels.AudioTrackFeature
import livekit.LivekitRtc
import livekit.LivekitRtc.SubscribedCodec
import livekit.LivekitRtc.SubscribedQuality
import livekit.org.webrtc.RtpParameters
import livekit.org.webrtc.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.kotlin.argThat
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class LocalParticipantMockE2ETest : MockE2ETest() {

    @Test
    fun disconnectCleansLocalParticipant() = runTest {
        connect()

        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(),
        )

        room.disconnect()

        assertEquals("", room.localParticipant.sid.value)
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
    fun setTrackEnabledIsSynchronizedSingleSource() = runTest {
        connect()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowApplication = Shadows.shadowOf(context as Application)
        shadowApplication.grantPermissions(Manifest.permission.RECORD_AUDIO)
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)
        wsFactory.ws.clearRequests()

        val standardTestDispatcher = StandardTestDispatcher()
        val backgroundScope = CoroutineScope(coroutineContext + Job() + standardTestDispatcher)
        try {
            backgroundScope.launch {
                try {
                    room.localParticipant.setMicrophoneEnabled(true)
                } catch (_: Exception) {
                }
            }
            backgroundScope.launch {
                try {
                    room.localParticipant.setMicrophoneEnabled(true)
                } catch (_: Exception) {
                }
            }

            standardTestDispatcher.scheduler.advanceTimeBy(1.seconds.inWholeMilliseconds)
            assertEquals(1, wsFactory.ws.sentRequests.size)
        } finally {
            backgroundScope.cancel()
        }
    }

    @Test
    fun setTrackEnabledIsSynchronizedMultipleSource() = runTest {
        connect()

        MockCameraProvider.register()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowApplication = Shadows.shadowOf(context as Application)
        shadowApplication.grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)
        wsFactory.ws.clearRequests()

        val standardTestDispatcher = StandardTestDispatcher()
        val backgroundScope = CoroutineScope(coroutineContext + Job() + standardTestDispatcher)
        try {
            backgroundScope.launch {
                try {
                    room.localParticipant.setMicrophoneEnabled(true)
                } catch (_: Exception) {
                }
            }
            backgroundScope.launch {
                try {
                    room.localParticipant.setCameraEnabled(true)
                } catch (_: Exception) {
                }
            }

            standardTestDispatcher.scheduler.advanceTimeBy(1.seconds.inWholeMilliseconds)

            assertEquals(2, wsFactory.ws.sentRequests.size)
        } finally {
            backgroundScope.cancel()
        }
    }

    @Test
    fun publishVideoTrackRequest() = runTest {
        connect()
        wsFactory.ws.clearRequests()
        val videoTrack = createLocalTrack()
        val publishOptions = VideoTrackPublishOptions(
            name = "name",
            source = Track.Source.SCREEN_SHARE,
            stream = "stream_id",
        )
        room.localParticipant.publishVideoTrack(videoTrack, publishOptions)

        // Verify the add track request gets the proper publish options set.
        val requestString = wsFactory.ws.sentRequests.first().toPBByteString()
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()

        assertTrue(sentRequest.hasAddTrack())
        assertEquals(publishOptions.name, sentRequest.addTrack.name)
        assertEquals(publishOptions.source?.toProto(), sentRequest.addTrack.source)
        assertEquals(publishOptions.stream, sentRequest.addTrack.stream)
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
    fun updateAttributes() = runTest {
        connect()
        wsFactory.ws.clearRequests()

        val newAttributes = mapOf("attribute" to "changedValue")
        room.localParticipant.updateAttributes(newAttributes)

        val requestString = wsFactory.ws.sentRequests.first().toPBByteString()
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()

        assertTrue(sentRequest.hasUpdateMetadata())
        assertEquals(newAttributes, sentRequest.updateMetadata.attributesMap)
    }

    @Test
    fun participantMetadataChanged() = runTest {
        connect()

        val roomEventsCollector = EventCollector(room.events, coroutineRule.scope)
        val participantEventsCollector = EventCollector(room.localParticipant.events, coroutineRule.scope)

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.LOCAL_PARTICIPANT_METADATA_CHANGED.toOkioByteString(),
        )

        val roomEvents = roomEventsCollector.stopCollecting()
        val participantEvents = participantEventsCollector.stopCollecting()

        val localParticipant = room.localParticipant
        val updateData = TestData.REMOTE_PARTICIPANT_METADATA_CHANGED.update.getParticipants(0)
        assertEquals(updateData.metadata, localParticipant.metadata)
        assertEquals(updateData.name, localParticipant.name)

        assertIsClassList(
            listOf(
                RoomEvent.ParticipantMetadataChanged::class.java,
                RoomEvent.ParticipantNameChanged::class.java,
                RoomEvent.ParticipantAttributesChanged::class.java,
            ),
            roomEvents,
        )

        assertIsClassList(
            listOf(
                ParticipantEvent.MetadataChanged::class.java,
                ParticipantEvent.NameChanged::class.java,
                ParticipantEvent.AttributesChanged::class.java,
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

    @Test
    fun publishDegradationPreferences() = runTest {
        val preference = RtpParameters.DegradationPreference.DISABLED
        room.videoTrackPublishDefaults = room.videoTrackPublishDefaults.copy(degradationPreference = preference)
        connect()

        room.localParticipant.publishVideoTrack(track = createLocalTrack())

        val peerConnection = getPublisherPeerConnection()
        val transceiver = peerConnection.transceivers.first()

        assertEquals(preference, transceiver.sender.parameters.degradationPreference)
    }

    @Test
    fun sendsInitialAudioTrackFeatures() = runTest {
        connect()

        wsFactory.ws.clearRequests()
        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(),
        )

        advanceUntilIdle()
        assertEquals(2, wsFactory.ws.sentRequests.size)

        // Verify the update audio track request gets the proper publish options set.
        val requestString = wsFactory.ws.sentRequests[1].toPBByteString()
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()

        assertTrue(sentRequest.hasUpdateAudioTrack())
        val features = sentRequest.updateAudioTrack.featuresList
        assertEquals(3, features.size)
        assertTrue(features.contains(AudioTrackFeature.TF_ECHO_CANCELLATION))
        assertTrue(features.contains(AudioTrackFeature.TF_NOISE_SUPPRESSION))
        assertTrue(features.contains(AudioTrackFeature.TF_AUTO_GAIN_CONTROL))
    }

    @Test
    fun sendsUpdatedAudioTrackFeatures() = runTest {
        connect()

        val audioProcessingController = MockAudioProcessingController()
        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(audioProcessingController = audioProcessingController),
        )

        advanceUntilIdle()
        wsFactory.ws.clearRequests()

        audioProcessingController.capturePostProcessor = object : AudioProcessorInterface {
            override fun isEnabled(): Boolean = true

            override fun getName(): String = "krisp_noise_cancellation"

            override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {}

            override fun resetAudioProcessing(newRate: Int) {}

            override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {}
        }
        assertEquals(1, wsFactory.ws.sentRequests.size)

        // Verify the update audio track request gets the proper publish options set.
        val requestString = wsFactory.ws.sentRequests[0].toPBByteString()
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()

        assertTrue(sentRequest.hasUpdateAudioTrack())
        val features = sentRequest.updateAudioTrack.featuresList
        assertEquals(4, features.size)
        assertTrue(features.contains(AudioTrackFeature.TF_ECHO_CANCELLATION))
        assertTrue(features.contains(AudioTrackFeature.TF_NOISE_SUPPRESSION))
        assertTrue(features.contains(AudioTrackFeature.TF_AUTO_GAIN_CONTROL))
        assertTrue(features.contains(AudioTrackFeature.TF_ENHANCED_NOISE_CANCELLATION))
    }

    @Test
    fun bypassUpdatesAudioFeatures() = runTest {
        connect()

        val audioProcessingController = MockAudioProcessingController()
        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(audioProcessingController = audioProcessingController),
        )

        advanceUntilIdle()
        wsFactory.ws.clearRequests()

        audioProcessingController.capturePostProcessor = object : AudioProcessorInterface {
            override fun isEnabled(): Boolean = true

            override fun getName(): String = "krisp_noise_cancellation"

            override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {}

            override fun resetAudioProcessing(newRate: Int) {}

            override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {}
        }
        assertEquals(1, wsFactory.ws.sentRequests.size)

        wsFactory.ws.clearRequests()

        audioProcessingController.bypassCapturePostProcessing = true
        assertEquals(1, wsFactory.ws.sentRequests.size)
        // Verify the update audio track request gets the proper publish options set.
        val requestString = wsFactory.ws.sentRequests[0].toPBByteString()
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()

        assertTrue(sentRequest.hasUpdateAudioTrack())
        val features = sentRequest.updateAudioTrack.featuresList
        assertEquals(3, features.size)
        assertTrue(features.contains(AudioTrackFeature.TF_ECHO_CANCELLATION))
        assertTrue(features.contains(AudioTrackFeature.TF_NOISE_SUPPRESSION))
        assertTrue(features.contains(AudioTrackFeature.TF_AUTO_GAIN_CONTROL))
        assertFalse(features.contains(AudioTrackFeature.TF_ENHANCED_NOISE_CANCELLATION))
    }

    @Test
    fun lackOfPublishPermissionCausesException() = runTest {
        val noCanPublishJoin = with(TestData.JOIN.toBuilder()) {
            join = with(join.toBuilder()) {
                participant = with(participant.toBuilder()) {
                    permission = with(permission.toBuilder()) {
                        canPublish = false
                        build()
                    }
                    build()
                }
                build()
            }
            build()
        }
        connect(noCanPublishJoin)

        var didThrow = false
        try {
            room.localParticipant.publishVideoTrack(createLocalTrack())
        } catch (e: TrackException.PublishException) {
            didThrow = true
        }

        assertTrue(didThrow)
    }

    @Test
    fun publishWithNoResponseCausesException() = runTest {
        connect()

        wsFactory.unregisterSignalRequestHandler(wsFactory.defaultSignalRequestHandler)
        var didThrow = false
        launch {
            try {
                room.localParticipant.publishVideoTrack(createLocalTrack())
            } catch (e: TrackException.PublishException) {
                didThrow = true
            }
        }

        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertTrue(didThrow)
    }
}
