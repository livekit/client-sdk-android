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

package io.livekit.android.room.framemetadata

import android.Manifest
import android.app.Application
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.participant.BackupVideoCodec
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.FrameMetadata
import io.livekit.android.room.track.FrameMetadataFeature
import io.livekit.android.room.track.FrameMetadataPublishOptions
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockEglBase
import io.livekit.android.test.mock.MockMediaStream
import io.livekit.android.test.mock.MockRTCThreadToken
import io.livekit.android.test.mock.MockRtpReceiver
import io.livekit.android.test.mock.MockVideoCapturer
import io.livekit.android.test.mock.MockVideoSource
import io.livekit.android.test.mock.MockVideoStreamTrack
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.camera.MockCameraProvider
import io.livekit.android.test.mock.createMediaStreamId
import io.livekit.android.test.mock.e2ee.NoopKeyProvider
import io.livekit.android.test.mock.room.track.createMockLocalAudioTrack
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitModels
import livekit.LivekitModels.PacketTrailerFeature
import livekit.LivekitRtc
import livekit.org.webrtc.FrameCryptor
import livekit.org.webrtc.FrameMetadataTransformer
import livekit.org.webrtc.FrameTransformer
import livekit.org.webrtc.FrameTransformerChain
import livekit.org.webrtc.MockPeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpSender
import livekit.org.webrtc.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import livekit.org.webrtc.FrameMetadata as RtcFrameMetadata

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FrameMetadataMockE2ETest : MockE2ETest() {

    private val factory
        get() = component.frameTransformerFactory()

    private val metadataOptions = FrameMetadataPublishOptions(timestamp = true, frameId = true)

    private fun createLocalTrack(source: VideoSource = MockVideoSource()) = LocalVideoTrack(
        capturer = MockVideoCapturer(),
        source = source,
        name = "",
        options = LocalVideoTrackOptions(
            isScreencast = false,
            deviceId = null,
            position = null,
            captureParams = VideoCaptureParameter(width = 1280, height = 720, maxFps = 30),
        ),
        rtcTrack = MockVideoStreamTrack(),
        peerConnectionFactory = component.peerConnectionFactory(),
        context = context,
        eglBase = MockEglBase(),
        defaultsManager = DefaultsManager(),
        trackFactory = mock(LocalVideoTrack.Factory::class.java),
        rtcThreadToken = MockRTCThreadToken(),
    )

    private fun sentAddTrackRequests(): List<LivekitRtc.AddTrackRequest> =
        wsFactory.ws.sentRequests
            .map { LivekitRtc.SignalRequest.newBuilder().mergeFrom(it.toPBByteString()).build() }
            .filter { it.hasAddTrack() }
            .map { it.addTrack }

    private fun sendTransformers() = factory.metadataTransformers.filter { it.direction == FrameMetadataTransformer.Direction.SEND }

    private fun receiveTransformers() = factory.metadataTransformers.filter { it.direction == FrameMetadataTransformer.Direction.RECEIVE }

    private fun enableE2EE() {
        room.e2eeOptions = E2EEOptions(keyProvider = NoopKeyProvider())
    }

    private fun verifySingleInstall(sender: RtpSender, transformer: FrameTransformer) {
        Mockito.verify(sender).setFrameTransformer(transformer)
        Mockito.verify(sender, Mockito.times(1)).setFrameTransformer(Mockito.any())
    }

    private fun verifySingleInstall(receiver: RtpReceiver, transformer: FrameTransformer) {
        Mockito.verify(receiver).setFrameTransformer(transformer)
        Mockito.verify(receiver, Mockito.times(1)).setFrameTransformer(Mockito.any())
    }

    // Publishing

    @Test
    fun publishSendsThePacketTrailerFeatures() = runTest {
        connect()
        wsFactory.ws.clearRequests()

        room.localParticipant.publishVideoTrack(
            createLocalTrack(),
            VideoTrackPublishOptions(frameMetadata = FrameMetadataPublishOptions(timestamp = true, frameId = true, userData = true)),
        )

        assertEquals(
            listOf(PacketTrailerFeature.PTF_USER_TIMESTAMP, PacketTrailerFeature.PTF_FRAME_ID, PacketTrailerFeature.PTF_USER_DATA),
            sentAddTrackRequests().single().packetTrailerFeaturesList,
        )
    }

    @Test
    fun publishDefaultsCarryFrameMetadata() = runTest {
        room.videoTrackPublishDefaults = room.videoTrackPublishDefaults.copy(frameMetadata = metadataOptions)
        connect()
        wsFactory.ws.clearRequests()

        room.localParticipant.publishVideoTrack(createLocalTrack())

        assertEquals(
            listOf(PacketTrailerFeature.PTF_USER_TIMESTAMP, PacketTrailerFeature.PTF_FRAME_ID),
            sentAddTrackRequests().single().packetTrailerFeaturesList,
        )
        assertEquals(1, sendTransformers().size)
    }

    @Test
    fun publishWithoutFrameMetadataSendsNoFeaturesAndCreatesNoTransformer() = runTest {
        connect()
        wsFactory.ws.clearRequests()
        val track = createLocalTrack()

        room.localParticipant.publishVideoTrack(track)

        assertTrue(sentAddTrackRequests().single().packetTrailerFeaturesList.isEmpty())
        assertTrue(factory.metadataTransformers.isEmpty())
        Mockito.verify(track.sender!!, Mockito.never()).setFrameTransformer(Mockito.any())
        assertFalse(track.frameMetadataStampState.isArmed)
    }

    @Test
    fun publishLinksArmsAndInstallsTheSendTransformer() = runTest {
        connect()
        val source = MockVideoSource()
        val track = createLocalTrack(source)

        room.localParticipant.publishVideoTrack(track, VideoTrackPublishOptions(frameMetadata = metadataOptions))

        val transformer = sendTransformers().single()
        assertSame(transformer, track.withFrameMetadataTransformer { it })
        assertEquals(listOf(transformer), source.frameMetadataTransformers)
        assertTrue(track.frameMetadataStampState.isArmed)
        verifySingleInstall(track.sender!!, transformer)
        assertTrue(factory.chains.isEmpty())
    }

    @Test
    fun unpublishUnlinksDisarmsAndDisposes() = runTest {
        connect()
        val source = MockVideoSource()
        val track = createLocalTrack(source)
        room.localParticipant.publishVideoTrack(track, VideoTrackPublishOptions(frameMetadata = metadataOptions))
        val transformer = sendTransformers().single()

        room.localParticipant.unpublishTrack(track)

        assertTrue(source.frameMetadataTransformers.isEmpty())
        assertNull(track.withFrameMetadataTransformer { it })
        assertFalse(track.frameMetadataStampState.isArmed)
        Mockito.verify(transformer).dispose()
    }

    @Test
    fun audioPublishCreatesNoTransformer() = runTest {
        connect()
        wsFactory.ws.clearRequests()

        room.localParticipant.publishAudioTrack(createMockLocalAudioTrack())

        assertTrue(sentAddTrackRequests().single().packetTrailerFeaturesList.isEmpty())
        assertTrue(factory.metadataTransformers.isEmpty())
    }

    // Publishing with E2EE

    @Test
    fun e2eeChainsTheCryptorBeforeTheMetadataTransformerOnSend() = runTest {
        enableE2EE()
        connect()
        val track = createLocalTrack()

        room.localParticipant.publishVideoTrack(track, VideoTrackPublishOptions(frameMetadata = metadataOptions))

        val created = factory.chains.single()
        assertSame(factory.senderCryptors.single(), created.first)
        assertSame(sendTransformers().single(), created.second)
        verifySingleInstall(track.sender!!, created.chain)
    }

    @Test
    fun e2eeOnlyVideoInstallsTheCryptorWithoutAChain() = runTest {
        enableE2EE()
        connect()
        val track = createLocalTrack()

        room.localParticipant.publishVideoTrack(track)

        assertTrue(factory.chains.isEmpty())
        verifySingleInstall(track.sender!!, factory.senderCryptors.single())
    }

    @Test
    fun e2eeAudioInstallsTheCryptorOnce() = runTest {
        enableE2EE()
        connect()
        val track = createMockLocalAudioTrack()

        room.localParticipant.publishAudioTrack(track)

        assertTrue(factory.chains.isEmpty())
        assertTrue(factory.metadataTransformers.isEmpty())
        verifySingleInstall(track.sender!!, factory.senderCryptors.single())
    }

    @Test
    fun e2eeUnpublishDisposesTheChain() = runTest {
        enableE2EE()
        connect()
        val track = createLocalTrack()
        room.localParticipant.publishVideoTrack(track, VideoTrackPublishOptions(frameMetadata = metadataOptions))
        val created = factory.chains.single()

        room.localParticipant.unpublishTrack(track)

        Mockito.verify(created.chain).dispose()
        Mockito.verify(sendTransformers().single()).dispose()
    }

    @Test
    fun chainForATrackRemovedBeforeItIsKeptIsDisposed() = runTest {
        // A send install keeps its chain after releasing the track's lock, so an unpublish can
        // remove the track in between.
        val manager = FrameMetadataManager(factory.factory)
        val chain = mock(FrameTransformerChain::class.java)

        manager.setChain(createLocalTrack(), chain)

        Mockito.verify(chain).dispose()
    }

    // Backup codecs and restarts

    private suspend fun publishVp9WithBackup(track: LocalVideoTrack) {
        room.localParticipant.publishVideoTrack(
            track,
            VideoTrackPublishOptions(
                videoCodec = VideoCodec.VP9.codecName,
                backupCodec = BackupVideoCodec(codec = VideoCodec.VP8.codecName),
                frameMetadata = metadataOptions,
            ),
        )
    }

    private fun requestBackupCodec() {
        val quality = LivekitRtc.SubscribedQuality.newBuilder()
            .setQuality(LivekitModels.VideoQuality.HIGH)
            .setEnabled(true)
            .build()
        wsFactory.receiveMessage(
            with(LivekitRtc.SignalResponse.newBuilder()) {
                subscribedQualityUpdate = with(LivekitRtc.SubscribedQualityUpdate.newBuilder()) {
                    trackSid = room.localParticipant.videoTrackPublications.first().first.sid
                    addSubscribedCodecs(LivekitRtc.SubscribedCodec.newBuilder().setCodec("vp9").addQualities(quality))
                    addSubscribedCodecs(LivekitRtc.SubscribedCodec.newBuilder().setCodec("vp8").addQualities(quality))
                    build()
                }
                build().toOkioByteString()
            },
        )
    }

    @Test
    fun backupCodecSenderGetsThePrimarysTransformer() = runTest {
        connect()
        val track = createLocalTrack()
        publishVp9WithBackup(track)

        requestBackupCodec()

        val transceivers = getPublisherPeerConnection().transceivers
        assertEquals(2, transceivers.size)
        val transformer = sendTransformers().single()
        verifySingleInstall(transceivers.last().sender, transformer)
    }

    private fun grantCamera() {
        MockCameraProvider.register()
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.CAMERA)
    }

    private val createdVideoSources
        get() = (component.peerConnectionFactory() as MockPeerConnectionFactory).createdVideoSources

    @Test
    fun restartTrackRelinksTheTransformerAndKeepsTheStampState() = runTest {
        connect()
        grantCamera()
        val track = room.localParticipant.createVideoTrack()
        room.localParticipant.publishVideoTrack(track, VideoTrackPublishOptions(frameMetadata = metadataOptions))
        val transformer = sendTransformers().single()
        val oldSource = createdVideoSources.last()
        val oldStamper = track.frameMetadataStamper!!
        assertSame(oldStamper, oldSource.videoProcessor)

        track.restartTrack()

        val newSource = createdVideoSources.last()
        assertTrue(oldSource.frameMetadataTransformers.isEmpty())
        assertEquals(listOf(transformer), newSource.frameMetadataTransformers)
        val newStamper = track.frameMetadataStamper!!
        assertSame(newStamper, newSource.videoProcessor)
        assertTrue(newStamper !== oldStamper)
        assertSame(track.frameMetadataStampState, newStamper.state)
        assertTrue(track.frameMetadataStampState.isArmed)
    }

    @Test
    fun restartTrackMovesBackupSendersToTheNewTrack() = runTest {
        connect()
        grantCamera()
        val track = room.localParticipant.createVideoTrack()
        publishVp9WithBackup(track)
        requestBackupCodec()
        val backupSender = getPublisherPeerConnection().transceivers.last().sender

        track.restartTrack()

        Mockito.verify(backupSender).setTrack(track.rtcTrack, false)
    }

    @Test
    fun restartTrackAfterUnpublishLinksNothing() = runTest {
        connect()
        grantCamera()
        val track = room.localParticipant.createVideoTrack()
        room.localParticipant.publishVideoTrack(track, VideoTrackPublishOptions(frameMetadata = metadataOptions))
        room.localParticipant.unpublishTrack(track, stopOnUnpublish = false)

        track.restartTrack()

        assertTrue(createdVideoSources.last().frameMetadataTransformers.isEmpty())
    }

    // Subscribing

    private fun joinRemoteParticipant(features: List<PacketTrailerFeature>) {
        val videoTrack = TestData.REMOTE_VIDEO_TRACK.toBuilder().addAllPacketTrailerFeatures(features).build()
        val participant = TestData.REMOTE_PARTICIPANT.toBuilder()
            .clearTracks()
            .addTracks(TestData.REMOTE_AUDIO_TRACK)
            .addTracks(videoTrack)
            .build()
        simulateMessageFromServer(
            LivekitRtc.SignalResponse.newBuilder()
                .setUpdate(LivekitRtc.ParticipantUpdate.newBuilder().addParticipants(participant))
                .build(),
        )
    }

    private fun subscribeRemoteVideo(): RtpReceiver {
        val receiver = MockRtpReceiver.create()
        room.onAddTrack(
            receiver,
            MockVideoStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(TestData.REMOTE_PARTICIPANT.sid, TestData.REMOTE_VIDEO_TRACK.sid),
                ),
            ),
        )
        return receiver
    }

    private fun remoteVideoPublication(): RemoteTrackPublication {
        val participant = room.getParticipantBySid(TestData.REMOTE_PARTICIPANT.sid)!!
        return participant.getTrackPublication(Track.Source.CAMERA) as RemoteTrackPublication
    }

    private fun remoteVideoTrack(): RemoteVideoTrack = remoteVideoPublication().track as RemoteVideoTrack

    /** Sends a participant update without the video track, which unpublishes it. */
    private fun unpublishRemoteVideo() {
        val participant = TestData.REMOTE_PARTICIPANT.toBuilder()
            .clearTracks()
            .addTracks(TestData.REMOTE_AUDIO_TRACK)
            .build()
        simulateMessageFromServer(
            LivekitRtc.SignalResponse.newBuilder()
                .setUpdate(LivekitRtc.ParticipantUpdate.newBuilder().addParticipants(participant))
                .build(),
        )
    }

    @Test
    fun subscribeWithFeaturesInstallsTheReceiveTransformerAndLooksUp() = runTest {
        connect()
        joinRemoteParticipant(listOf(PacketTrailerFeature.PTF_USER_TIMESTAMP))
        factory.lookupResult = { rtpTimestamp -> if (rtpTimestamp == 1234L) RtcFrameMetadata(55L, 6L, null) else null }

        val receiver = subscribeRemoteVideo()

        val transformer = receiveTransformers().single()
        verifySingleInstall(receiver, transformer)
        val track = remoteVideoTrack()
        assertEquals(FrameMetadata(userTimestampUs = 55L, frameId = 6L), track.lookupFrameMetadata(1234L))
        assertNull(track.lookupFrameMetadata(1L))
    }

    @Test
    fun remotePublicationExposesTheDeclaredFeatures() = runTest {
        connect()
        joinRemoteParticipant(listOf(PacketTrailerFeature.PTF_USER_TIMESTAMP, PacketTrailerFeature.PTF_USER_DATA))

        val publication = room.getParticipantBySid(TestData.REMOTE_PARTICIPANT.sid)!!.getTrackPublication(Track.Source.CAMERA)!!
        assertEquals(listOf(FrameMetadataFeature.TIMESTAMP, FrameMetadataFeature.USER_DATA), publication.frameMetadataFeatures)
    }

    @Test
    fun remotePublicationSkipsFeaturesThisBuildDoesNotKnow() = runTest {
        connect()
        val videoTrack = TestData.REMOTE_VIDEO_TRACK.toBuilder()
            .addPacketTrailerFeatures(PacketTrailerFeature.PTF_FRAME_ID)
            .addPacketTrailerFeaturesValue(99)
            .build()
        simulateMessageFromServer(
            LivekitRtc.SignalResponse.newBuilder()
                .setUpdate(
                    LivekitRtc.ParticipantUpdate.newBuilder().addParticipants(
                        TestData.REMOTE_PARTICIPANT.toBuilder().clearTracks().addTracks(videoTrack),
                    ),
                )
                .build(),
        )

        val publication = room.getParticipantBySid(TestData.REMOTE_PARTICIPANT.sid)!!.getTrackPublication(Track.Source.CAMERA)!!
        assertEquals(listOf(FrameMetadataFeature.FRAME_ID), publication.frameMetadataFeatures)
    }

    @Test
    fun subscribeWithoutFeaturesInstallsNothing() = runTest {
        connect()
        joinRemoteParticipant(emptyList())

        val receiver = subscribeRemoteVideo()

        assertTrue(factory.metadataTransformers.isEmpty())
        Mockito.verify(receiver, Mockito.never()).setFrameTransformer(Mockito.any())
        assertNull(remoteVideoTrack().lookupFrameMetadata(1234L))
    }

    @Test
    fun e2eeChainsTheMetadataTransformerBeforeTheCryptorOnReceive() = runTest {
        enableE2EE()
        connect()
        joinRemoteParticipant(listOf(PacketTrailerFeature.PTF_FRAME_ID))

        val receiver = subscribeRemoteVideo()

        val created = factory.chains.single()
        assertSame(receiveTransformers().single(), created.first)
        assertSame(factory.receiverCryptors.single(), created.second)
        verifySingleInstall(receiver, created.chain)
    }

    @Test
    fun unsubscribeDisposesWithoutDisablingAndLookupReturnsNull() = runTest {
        connect()
        joinRemoteParticipant(listOf(PacketTrailerFeature.PTF_USER_TIMESTAMP))
        factory.lookupResult = { RtcFrameMetadata(55L, 6L, null) }
        subscribeRemoteVideo()
        val track = remoteVideoTrack()
        val transformer = receiveTransformers().single()
        assertNotNull(track.lookupFrameMetadata(1234L))

        simulateMessageFromServer(TestData.PARTICIPANT_DISCONNECT)

        Mockito.verify(transformer).dispose()
        Mockito.verify(transformer, Mockito.never()).setEnabled(false)
        assertNull(track.lookupFrameMetadata(1234L))
    }

    @Test
    fun receiveInstallAfterRemovalInstallsOnlyTheCryptor() = runTest {
        // What an install sees when a removal gets the manager's lock first.
        enableE2EE()
        connect()
        joinRemoteParticipant(listOf(PacketTrailerFeature.PTF_USER_TIMESTAMP))
        val receiver = subscribeRemoteVideo()
        val track = remoteVideoTrack()
        unpublishRemoteVideo()
        val cryptor = mock(FrameCryptor::class.java)

        room.installReceiveTransformers(track, cryptor)

        assertEquals(1, factory.chains.size)
        Mockito.verify(receiver).setFrameTransformer(cryptor)
    }

    @Test
    fun receiveReinstallReplacesAndDisposesThePreviousChain() = runTest {
        enableE2EE()
        connect()
        joinRemoteParticipant(listOf(PacketTrailerFeature.PTF_USER_TIMESTAMP))
        subscribeRemoteVideo()
        val first = factory.chains.single()

        room.installReceiveTransformers(remoteVideoTrack(), mock(FrameCryptor::class.java))

        val second = factory.chains.single { it !== first }
        assertSame(first.first, second.first)
        Mockito.verify(first.chain).dispose()
        Mockito.verify(second.chain, Mockito.never()).dispose()
    }

    @Test
    fun resubscribeDisposesThePreviousTransformerAndChain() = runTest {
        enableE2EE()
        connect()
        joinRemoteParticipant(listOf(PacketTrailerFeature.PTF_USER_TIMESTAMP))
        factory.lookupResult = { RtcFrameMetadata(55L, 6L, null) }
        subscribeRemoteVideo()
        val firstTrack = remoteVideoTrack()
        val firstTransformer = receiveTransformers().single()
        val firstChain = factory.chains.single().chain

        // The client isn't told when the server stops sending the track, so the next subscribe
        // arrives with the first track still in place.
        remoteVideoPublication().setSubscribed(false)
        remoteVideoPublication().setSubscribed(true)
        subscribeRemoteVideo()

        val secondTrack = remoteVideoTrack()
        assertNotSame(firstTrack, secondTrack)
        Mockito.verify(firstTransformer).dispose()
        Mockito.verify(firstChain).dispose()
        assertNull(firstTrack.lookupFrameMetadata(1234L))
        val secondTransformer = receiveTransformers().single { it !== firstTransformer }
        Mockito.verify(secondTransformer, Mockito.never()).dispose()
        Mockito.verify(factory.chains.single { it.chain !== firstChain }.chain, Mockito.never()).dispose()
        assertNotNull(secondTrack.lookupFrameMetadata(1234L))
    }

    @Test
    fun unpublishAfterResubscribeDisposesEachTransformerOnce() = runTest {
        connect()
        joinRemoteParticipant(listOf(PacketTrailerFeature.PTF_USER_TIMESTAMP))
        subscribeRemoteVideo()
        subscribeRemoteVideo()

        unpublishRemoteVideo()

        assertEquals(2, receiveTransformers().size)
        receiveTransformers().forEach { Mockito.verify(it).dispose() }
    }

    @Test
    fun disconnectDisposesEverything() = runTest {
        connect()
        room.localParticipant.publishVideoTrack(createLocalTrack(), VideoTrackPublishOptions(frameMetadata = metadataOptions))
        joinRemoteParticipant(listOf(PacketTrailerFeature.PTF_USER_TIMESTAMP))
        subscribeRemoteVideo()

        room.disconnect()

        assertEquals(2, factory.metadataTransformers.size)
        factory.metadataTransformers.forEach { Mockito.verify(it).dispose() }
    }

    // Capabilities

    @Test
    fun connectAdvertisesPacketTrailers() = runTest {
        connect()

        val capabilities = wsFactory.request.url.queryParameter("capabilities")!!.split(",")
        assertTrue(capabilities.contains("CAP_PACKET_TRAILER"))
    }
}
