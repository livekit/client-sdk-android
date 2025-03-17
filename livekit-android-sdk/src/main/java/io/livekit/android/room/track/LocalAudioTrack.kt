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

package io.livekit.android.room.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.audio.AudioBufferCallbackDispatcher
import io.livekit.android.audio.AudioProcessingController
import io.livekit.android.audio.AudioRecordPrewarmer
import io.livekit.android.audio.AudioRecordSamplesDispatcher
import io.livekit.android.audio.MixerAudioBufferCallback
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flow
import io.livekit.android.util.flowDelegate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import livekit.LivekitModels.AudioTrackFeature
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpSender
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.audio.AudioDeviceModule
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import javax.inject.Named

/**
 * Represents a local audio track (generally using the microphone as input).
 *
 * This class should not be constructed directly, but rather through [LocalParticipant.createAudioTrack].
 */
class LocalAudioTrack
@AssistedInject
constructor(
    @Assisted name: String,
    @Assisted mediaTrack: livekit.org.webrtc.AudioTrack,
    @Assisted private val options: LocalAudioTrackOptions,
    private val audioProcessingController: AudioProcessingController,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    private val dispatcher: CoroutineDispatcher,
    @Named(InjectionNames.LOCAL_AUDIO_RECORD_SAMPLES_DISPATCHER)
    private val audioRecordSamplesDispatcher: AudioRecordSamplesDispatcher,
    @Named(InjectionNames.LOCAL_AUDIO_BUFFER_CALLBACK_DISPATCHER)
    private val audioBufferCallbackDispatcher: AudioBufferCallbackDispatcher,
    private val audioRecordPrewarmer: AudioRecordPrewarmer,
) : AudioTrack(name, mediaTrack) {
    /**
     * To only be used for flow delegate scoping, and should not be cancelled.
     **/
    private val delegateScope = CoroutineScope(dispatcher + SupervisorJob())

    internal var transceiver: RtpTransceiver? = null
    internal val sender: RtpSender?
        get() = transceiver?.sender

    private val trackSinks = mutableSetOf<AudioTrackSink>()

    /**
     * Prewarms the audio stack if needed by starting the recording regardless of whether it's being published.
     */
    fun prewarm() {
        audioRecordPrewarmer.prewarm()
    }

    fun stopPrewarm() {
        audioRecordPrewarmer.stop()
    }

    /**
     * Note: This function relies on us setting
     * [JavaAudioDeviceModule.Builder.setSamplesReadyCallback].
     * If you provide your own [AudioDeviceModule], or set your own
     * callback, your sink will not receive any audio data.
     *
     * @see AudioTrack.addSink
     */
    override fun addSink(sink: AudioTrackSink) {
        synchronized(trackSinks) {
            trackSinks.add(sink)
            audioRecordSamplesDispatcher.registerSink(sink)
        }
    }

    override fun removeSink(sink: AudioTrackSink) {
        synchronized(trackSinks) {
            trackSinks.remove(sink)
            audioRecordSamplesDispatcher.unregisterSink(sink)
        }
    }

    /**
     * Use this method to mix in custom audio.
     *
     * See [MixerAudioBufferCallback] for automatic handling of mixing in
     * the provided audio data.
     */
    fun setAudioBufferCallback(callback: AudioBufferCallback?) {
        audioBufferCallbackDispatcher.bufferCallback = callback
    }

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    val features by flowDelegate(
        stateFlow = combine(
            audioProcessingController::capturePostProcessor.flow,
            audioProcessingController::bypassCapturePostProcessing.flow,
        ) { processor, bypass ->
            processor to bypass
        }
            .map {
                val features = getConstantFeatures()
                val (processor, bypass) = it
                if (!bypass && processor?.getName() == "krisp_noise_cancellation") {
                    features.add(AudioTrackFeature.TF_ENHANCED_NOISE_CANCELLATION)
                }
                return@map features
            }
            .stateIn(delegateScope, SharingStarted.Eagerly, emptySet()),
    )

    private fun getConstantFeatures(): MutableSet<AudioTrackFeature> {
        val features = mutableSetOf<AudioTrackFeature>()

        if (options.echoCancellation) {
            features.add(AudioTrackFeature.TF_ECHO_CANCELLATION)
        }
        if (options.noiseSuppression) {
            features.add(AudioTrackFeature.TF_NOISE_SUPPRESSION)
        }
        if (options.autoGainControl) {
            features.add(AudioTrackFeature.TF_AUTO_GAIN_CONTROL)
        }
        // TODO: Handle getting other info from JavaAudioDeviceModule
        return features
    }

    override fun dispose() {
        synchronized(trackSinks) {
            for (sink in trackSinks) {
                trackSinks.remove(sink)
                audioRecordSamplesDispatcher.unregisterSink(sink)
            }
        }
        super.dispose()
    }

    companion object {
        internal fun createTrack(
            context: Context,
            factory: PeerConnectionFactory,
            options: LocalAudioTrackOptions = LocalAudioTrackOptions(),
            audioTrackFactory: Factory,
            name: String = "",
        ): LocalAudioTrack {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                LKLog.w { "Record audio permissions not granted, microphone recording will not be used." }
            }

            val audioConstraints = MediaConstraints()
            val items = listOf(
                MediaConstraints.KeyValuePair("googEchoCancellation", options.echoCancellation.toString()),
                MediaConstraints.KeyValuePair("googAutoGainControl", options.autoGainControl.toString()),
                MediaConstraints.KeyValuePair("googHighpassFilter", options.highPassFilter.toString()),
                MediaConstraints.KeyValuePair("googNoiseSuppression", options.noiseSuppression.toString()),
                MediaConstraints.KeyValuePair("googTypingNoiseDetection", options.typingNoiseDetection.toString()),
            )
            audioConstraints.optional.addAll(items)

            val audioSource = factory.createAudioSource(audioConstraints)
            val rtcAudioTrack =
                factory.createAudioTrack(UUID.randomUUID().toString(), audioSource)

            return audioTrackFactory.create(name = name, mediaTrack = rtcAudioTrack, options = options)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            name: String,
            mediaTrack: livekit.org.webrtc.AudioTrack,
            options: LocalAudioTrackOptions,
        ): LocalAudioTrack
    }
}
