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

package io.livekit.android.dagger

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.Nullable
import dagger.Module
import dagger.Provides
import io.livekit.android.LiveKit
import io.livekit.android.memory.CloseableManager
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import io.livekit.android.webrtc.SimulcastVideoEncoderFactoryWrapper
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber
import javax.inject.Named
import javax.inject.Singleton

typealias CapabilitiesGetter = @JvmSuppressWildcards (MediaStreamTrack.MediaType) -> RtpCapabilities

@Module
object RTCModule {
    @Provides
    @Singleton
    @JvmSuppressWildcards
    fun audioModule(
        @Named(InjectionNames.OVERRIDE_AUDIO_DEVICE_MODULE)
        @Nullable
        audioDeviceModuleOverride: AudioDeviceModule?,
        @Named(InjectionNames.OVERRIDE_JAVA_AUDIO_DEVICE_MODULE_CUSTOMIZER)
        @Nullable
        moduleCustomizer: ((builder: JavaAudioDeviceModule.Builder) -> Unit)?,
        audioOutputAttributes: AudioAttributes,
        appContext: Context
    ): AudioDeviceModule {
        if (audioDeviceModuleOverride != null) {
            return audioDeviceModuleOverride
        }

        // Set audio record error callbacks.
        val audioRecordErrorCallback = object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                LKLog.e { "onWebRtcAudioRecordInitError: $errorMessage" }
            }

            override fun onWebRtcAudioRecordStartError(
                errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                errorMessage: String?
            ) {
                LKLog.e { "onWebRtcAudioRecordStartError: $errorCode. $errorMessage" }
            }

            override fun onWebRtcAudioRecordError(errorMessage: String?) {
                LKLog.e { "onWebRtcAudioRecordError: $errorMessage" }
            }
        }

        val audioTrackErrorCallback = object : JavaAudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                LKLog.e { "onWebRtcAudioTrackInitError: $errorMessage" }
            }

            override fun onWebRtcAudioTrackStartError(
                errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                errorMessage: String?
            ) {
                LKLog.e { "onWebRtcAudioTrackStartError: $errorCode. $errorMessage" }
            }

            override fun onWebRtcAudioTrackError(errorMessage: String?) {
                LKLog.e { "onWebRtcAudioTrackError: $errorMessage" }
            }

        }
        val audioRecordStateCallback: JavaAudioDeviceModule.AudioRecordStateCallback = object :
            JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() {
                LKLog.v { "Audio recording starts" }
            }

            override fun onWebRtcAudioRecordStop() {
                LKLog.v { "Audio recording stops" }
            }
        }

        // Set audio track state callbacks.
        val audioTrackStateCallback: JavaAudioDeviceModule.AudioTrackStateCallback = object :
            JavaAudioDeviceModule.AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                LKLog.v { "Audio playout starts" }
            }

            override fun onWebRtcAudioTrackStop() {
                LKLog.v { "Audio playout stops" }
            }
        }

        val useHardwareAudioProcessing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val builder = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(useHardwareAudioProcessing)
            .setUseHardwareNoiseSuppressor(useHardwareAudioProcessing)
            .setAudioRecordErrorCallback(audioRecordErrorCallback)
            .setAudioTrackErrorCallback(audioTrackErrorCallback)
            .setAudioRecordStateCallback(audioRecordStateCallback)
            .setAudioTrackStateCallback(audioTrackStateCallback)
            .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            .setAudioAttributes(audioOutputAttributes)

        moduleCustomizer?.invoke(builder)
        return builder.createAudioDeviceModule()
    }

    @Provides
    @Singleton
    fun eglBase(@Singleton memoryManager: CloseableManager): EglBase {
        val eglBase = EglBase.create()
        memoryManager.registerResource(eglBase) { eglBase.release() }

        return eglBase
    }

    @Provides
    fun eglContext(eglBase: EglBase): EglBase.Context = eglBase.eglBaseContext

    @Provides
    fun videoEncoderFactory(
        @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
        videoHwAccel: Boolean,
        eglContext: EglBase.Context,
        @Named(InjectionNames.OVERRIDE_VIDEO_ENCODER_FACTORY)
        @Nullable
        videoEncoderFactoryOverride: VideoEncoderFactory?
    ): VideoEncoderFactory {
        return videoEncoderFactoryOverride ?: if (videoHwAccel) {
            SimulcastVideoEncoderFactoryWrapper(
                eglContext,
                enableIntelVp8Encoder = true,
                enableH264HighProfile = false,
            )
        } else {
            SoftwareVideoEncoderFactory()
        }
    }

    @Provides
    fun videoDecoderFactory(
        @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
        videoHwAccel: Boolean,
        eglContext: EglBase.Context,
        @Named(InjectionNames.OVERRIDE_VIDEO_DECODER_FACTORY)
        @Nullable
        videoDecoderFactoryOverride: VideoDecoderFactory?
    ): VideoDecoderFactory {
        return videoDecoderFactoryOverride ?: if (videoHwAccel) {
            WrappedVideoDecoderFactory(eglContext)
        } else {
            SoftwareVideoDecoderFactory()
        }
    }

    @Provides
    @Singleton
    fun peerConnectionFactory(
        appContext: Context,
        audioDeviceModule: AudioDeviceModule,
        videoEncoderFactory: VideoEncoderFactory,
        videoDecoderFactory: VideoDecoderFactory,
    ): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(appContext)
                .setInjectableLogger({ s, severity, s2 ->
                    if (!LiveKit.enableWebRTCLogging) {
                        return@setInjectableLogger
                    }

                    val loggingLevel = when (severity) {
                        Logging.Severity.LS_VERBOSE -> LoggingLevel.VERBOSE
                        Logging.Severity.LS_INFO -> LoggingLevel.INFO
                        Logging.Severity.LS_WARNING -> LoggingLevel.WARN
                        Logging.Severity.LS_ERROR -> LoggingLevel.ERROR
                        else -> LoggingLevel.OFF
                    }

                    LKLog.log(loggingLevel) {
                        Timber.log(loggingLevel.toAndroidLogPriority(), "$s2: $s")
                    }
                }, Logging.Severity.LS_VERBOSE)
                .createInitializationOptions()
        )
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()
    }

    @Provides
    @Named(InjectionNames.SENDER)
    fun senderCapabilitiesGetter(peerConnectionFactory: PeerConnectionFactory): CapabilitiesGetter {
        return { mediaType: MediaStreamTrack.MediaType ->
            peerConnectionFactory.getRtpSenderCapabilities(mediaType)
        }
    }

    @Provides
    @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
    fun videoHwAccel() = true
}