/*
 * Copyright 2023-2026 LiveKit, Inc.
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

package io.livekit.android.room.track

import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.assert.assertIsClass
import io.livekit.android.test.coroutines.toListUntilSignal
import io.livekit.android.test.mock.MockAudioProcessingController
import io.livekit.android.test.mock.MockAudioStreamTrack
import io.livekit.android.test.mock.room.track.createMockLocalAudioTrack
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import livekit.LivekitModels.AudioTrackFeature
import livekit.LivekitRtc
import livekit.org.webrtc.audio.AudioProcessingOptionsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class LocalAudioTrackMockE2ETest : MockE2ETest() {

    @Test
    fun defaultFeatures() = runTest {
        val track = createMockLocalAudioTrack()
        advanceUntilIdle()

        assertConstantFeatures(track.features)
    }

    @Test
    fun featuresReflectInitialOptions() = runTest {
        val noEcho = createMockLocalAudioTrack(
            options = LocalAudioTrackOptions(echoCancellation = false),
        )
        advanceUntilIdle()
        assertEquals(
            setOf(
                AudioTrackFeature.TF_NOISE_SUPPRESSION,
                AudioTrackFeature.TF_AUTO_GAIN_CONTROL,
            ),
            noEcho.features,
        )

        val noNoiseSuppression = createMockLocalAudioTrack(
            options = LocalAudioTrackOptions(noiseSuppression = false),
        )
        advanceUntilIdle()
        assertEquals(
            setOf(
                AudioTrackFeature.TF_ECHO_CANCELLATION,
                AudioTrackFeature.TF_AUTO_GAIN_CONTROL,
            ),
            noNoiseSuppression.features,
        )

        val noAutoGainControl = createMockLocalAudioTrack(
            options = LocalAudioTrackOptions(autoGainControl = false),
        )
        advanceUntilIdle()
        assertEquals(
            setOf(
                AudioTrackFeature.TF_ECHO_CANCELLATION,
                AudioTrackFeature.TF_NOISE_SUPPRESSION,
            ),
            noAutoGainControl.features,
        )
    }

    @Test
    fun featuresWithAllProcessingDisabled() = runTest {
        val track = createMockLocalAudioTrack(
            options = LocalAudioTrackOptions(
                echoCancellation = false,
                noiseSuppression = false,
                autoGainControl = false,
            ),
        )
        advanceUntilIdle()

        assertTrue(track.features.isEmpty())
    }

    @Test
    fun featuresIncludeEnhancedNoiseCancellationForKrisp() = runTest {
        val audioProcessingController = MockAudioProcessingController()
        val track = createMockLocalAudioTrack(audioProcessingController = audioProcessingController)
        advanceUntilIdle()
        assertConstantFeatures(track.features)

        audioProcessingController.capturePostProcessor = krispProcessor()
        advanceUntilIdle()

        assertEquals(4, track.features.size)
        assertTrue(track.features.contains(AudioTrackFeature.TF_ECHO_CANCELLATION))
        assertTrue(track.features.contains(AudioTrackFeature.TF_NOISE_SUPPRESSION))
        assertTrue(track.features.contains(AudioTrackFeature.TF_AUTO_GAIN_CONTROL))
        assertTrue(track.features.contains(AudioTrackFeature.TF_ENHANCED_NOISE_CANCELLATION))
    }

    @Test
    fun featuresExcludeEnhancedNoiseCancellationForOtherProcessor() = runTest {
        val audioProcessingController = MockAudioProcessingController()
        val track = createMockLocalAudioTrack(audioProcessingController = audioProcessingController)
        advanceUntilIdle()

        audioProcessingController.capturePostProcessor = otherProcessor()
        advanceUntilIdle()

        assertConstantFeatures(track.features)
        assertFalse(track.features.contains(AudioTrackFeature.TF_ENHANCED_NOISE_CANCELLATION))
    }

    @Test
    fun featuresFlowEmitsOnApplyOptions() = runTest {
        val track = createMockLocalAudioTrack()
        val signal = MutableStateFlow<Unit?>(null)
        val job = async {
            track::features.flow.toListUntilSignal(signal)
        }

        advanceUntilIdle()
        track.applyOptions(LocalAudioTrackOptions(echoCancellation = false))
        advanceUntilIdle()

        signal.compareAndSet(null, Unit)
        val collectedList = job.await()

        assertEquals(2, collectedList.size)
        assertConstantFeatures(collectedList[0])
        assertEquals(
            setOf(
                AudioTrackFeature.TF_NOISE_SUPPRESSION,
                AudioTrackFeature.TF_AUTO_GAIN_CONTROL,
            ),
            collectedList[1],
        )
    }

    @Test
    fun featuresFlowEmitsOnProcessorChange() = runTest {
        val audioProcessingController = MockAudioProcessingController()
        val track = createMockLocalAudioTrack(audioProcessingController = audioProcessingController)
        val signal = MutableStateFlow<Unit?>(null)
        val job = async {
            track::features.flow.toListUntilSignal(signal)
        }

        advanceUntilIdle()
        audioProcessingController.capturePostProcessor = krispProcessor()
        advanceUntilIdle()

        signal.compareAndSet(null, Unit)
        val collectedList = job.await()

        assertEquals(2, collectedList.size)
        assertConstantFeatures(collectedList[0])
        assertTrue(collectedList[1].contains(AudioTrackFeature.TF_ENHANCED_NOISE_CANCELLATION))
    }

    @Test
    fun featuresFlowEmitsOnBypass() = runTest {
        val audioProcessingController = MockAudioProcessingController()
        val track = createMockLocalAudioTrack(audioProcessingController = audioProcessingController)
        val signal = MutableStateFlow<Unit?>(null)
        val job = async {
            track::features.flow.toListUntilSignal(signal)
        }

        advanceUntilIdle()
        audioProcessingController.capturePostProcessor = krispProcessor()
        advanceUntilIdle()
        audioProcessingController.bypassCapturePostProcessing = true
        advanceUntilIdle()

        signal.compareAndSet(null, Unit)
        val collectedList = job.await()

        assertEquals(3, collectedList.size)
        assertConstantFeatures(collectedList[0])
        assertTrue(collectedList[1].contains(AudioTrackFeature.TF_ENHANCED_NOISE_CANCELLATION))
        assertConstantFeatures(collectedList[2])
        assertFalse(collectedList[2].contains(AudioTrackFeature.TF_ENHANCED_NOISE_CANCELLATION))
    }

    @Test
    fun applyOptionsUpdatesFeatures() = runTest {
        val track = createMockLocalAudioTrack()
        advanceUntilIdle()
        assertConstantFeatures(track.features)

        val updatedOptions = LocalAudioTrackOptions(echoCancellation = false)
        assertTrue(track.applyOptions(updatedOptions).isSuccess)
        advanceUntilIdle()

        assertEquals(updatedOptions, track.options)
        assertEquals(
            setOf(
                AudioTrackFeature.TF_NOISE_SUPPRESSION,
                AudioTrackFeature.TF_AUTO_GAIN_CONTROL,
            ),
            track.features,
        )
    }

    @Test
    fun applyOptionsPassesProcessingOptionsToRtcTrack() = runTest {
        val mediaTrack = MockAudioStreamTrack()
        val track = createMockLocalAudioTrack(mediaTrack = mediaTrack)
        val updatedOptions = LocalAudioTrackOptions(
            echoCancellation = false,
            noiseSuppression = false,
            autoGainControl = true,
            highPassFilter = false,
        )

        assertTrue(track.applyOptions(updatedOptions).isSuccess)

        val appliedOptions = mediaTrack.lastAudioProcessingOptions
        assertEquals(false, appliedOptions?.echoCancellation)
        assertEquals(false, appliedOptions?.noiseSuppression)
        assertEquals(true, appliedOptions?.autoGainControl)
        assertEquals(false, appliedOptions?.highPassFilter)
    }

    @Test
    fun applyOptionsFailurePreservesFeatures() = runTest {
        val mediaTrack = MockAudioStreamTrack().apply {
            audioProcessingOptionsResult = AudioProcessingOptionsResult.rejected(
                AudioProcessingOptionsResult.Code.APPLY_FAILED,
                "failed",
            )
        }
        val track = createMockLocalAudioTrack(mediaTrack = mediaTrack)
        advanceUntilIdle()
        val originalFeatures = track.features

        val result = track.applyOptions(LocalAudioTrackOptions(echoCancellation = false))
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertIsClass(TrackException.MediaException::class.java, result.exceptionOrNull())
        assertEquals(originalFeatures, track.features)
        assertTrue(track.options.echoCancellation)
    }

    @Test
    fun applyOptionsFailureUsesFallbackMessage() = runTest {
        val mediaTrack = MockAudioStreamTrack().apply {
            audioProcessingOptionsResult = AudioProcessingOptionsResult.rejected(
                AudioProcessingOptionsResult.Code.APPLY_FAILED,
                "",
            )
        }
        val track = createMockLocalAudioTrack(mediaTrack = mediaTrack)

        val result = track.applyOptions(LocalAudioTrackOptions(echoCancellation = false))

        assertTrue(result.isFailure)
        assertIsClass(TrackException.MediaException::class.java, result.exceptionOrNull())
        assertEquals(
            "Failed to apply audio processing options (${AudioProcessingOptionsResult.Code.APPLY_FAILED})",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun applyOptionsOnDisposedTrackFails() = runTest {
        val track = createMockLocalAudioTrack()
        track.dispose()

        val result = track.applyOptions(LocalAudioTrackOptions(echoCancellation = false))

        assertTrue(result.isFailure)
        assertIsClass(TrackException.InvalidTrackStateException::class.java, result.exceptionOrNull())
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

        val features = lastUpdateAudioTrackFeatures()
        assertConstantFeatures(features)
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

        audioProcessingController.capturePostProcessor = krispProcessor()
        assertEquals(1, wsFactory.ws.sentRequests.size)

        val features = lastUpdateAudioTrackFeatures()
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

        audioProcessingController.capturePostProcessor = krispProcessor()
        assertEquals(1, wsFactory.ws.sentRequests.size)

        wsFactory.ws.clearRequests()

        audioProcessingController.bypassCapturePostProcessing = true
        assertEquals(1, wsFactory.ws.sentRequests.size)

        val features = lastUpdateAudioTrackFeatures()
        assertConstantFeatures(features)
        assertFalse(features.contains(AudioTrackFeature.TF_ENHANCED_NOISE_CANCELLATION))
    }

    @Test
    fun sendsUpdatedFeaturesOnApplyOptions() = runTest {
        connect()

        val track = createMockLocalAudioTrack()
        room.localParticipant.publishAudioTrack(track = track)

        advanceUntilIdle()
        wsFactory.ws.clearRequests()

        assertTrue(track.applyOptions(LocalAudioTrackOptions(echoCancellation = false)).isSuccess)
        advanceUntilIdle()
        assertEquals(1, wsFactory.ws.sentRequests.size)

        val features = lastUpdateAudioTrackFeatures()
        assertEquals(
            setOf(
                AudioTrackFeature.TF_NOISE_SUPPRESSION,
                AudioTrackFeature.TF_AUTO_GAIN_CONTROL,
            ),
            features,
        )
    }

    private fun lastUpdateAudioTrackFeatures(): Set<AudioTrackFeature> {
        val requestString = wsFactory.ws.sentRequests.last().toPBByteString()
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()

        assertTrue(sentRequest.hasUpdateAudioTrack())
        return sentRequest.updateAudioTrack.featuresList.toSet()
    }

    private fun assertConstantFeatures(features: Collection<AudioTrackFeature>) {
        assertEquals(3, features.size)
        assertTrue(features.contains(AudioTrackFeature.TF_ECHO_CANCELLATION))
        assertTrue(features.contains(AudioTrackFeature.TF_NOISE_SUPPRESSION))
        assertTrue(features.contains(AudioTrackFeature.TF_AUTO_GAIN_CONTROL))
    }

    private fun krispProcessor() = object : AudioProcessorInterface {
        override fun isEnabled(): Boolean = true

        override fun getName(): String = "krisp_noise_cancellation"

        override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {}

        override fun resetAudioProcessing(newRate: Int) {}

        override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {}
    }

    private fun otherProcessor() = object : AudioProcessorInterface {
        override fun isEnabled(): Boolean = true

        override fun getName(): String = "other_processor"

        override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {}

        override fun resetAudioProcessing(newRate: Int) {}

        override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {}
    }
}
