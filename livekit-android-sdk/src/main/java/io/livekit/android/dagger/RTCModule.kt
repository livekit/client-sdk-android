/*
 * Copyright 2023-2024 LiveKit, Inc.
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
import android.javax.sdp.SdpFactory
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.Nullable
import dagger.Module
import dagger.Provides
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioProcessingController
import io.livekit.android.audio.CommunicationWorkaround
import io.livekit.android.memory.CloseableManager
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import io.livekit.android.webrtc.CustomAudioProcessingFactory
import io.livekit.android.webrtc.CustomVideoDecoderFactory
import io.livekit.android.webrtc.CustomVideoEncoderFactory
import livekit.org.webrtc.*
import livekit.org.webrtc.audio.AudioDeviceModule
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber
import javax.inject.Named
import javax.inject.Singleton

internal typealias CapabilitiesGetter = @JvmSuppressWildcards (MediaStreamTrack.MediaType) -> RtpCapabilities

@Module
internal object RTCModule {

    /**
     * Certain classes require libwebrtc to be initialized prior to use.
     */
    @Provides
    @Singleton
    @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
    fun libWebrtcInitialization(appContext: Context): LibWebrtcInitialization {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(appContext)
                .setNativeLibraryName("lkjingle_peerconnection_so")
                .setInjectableLogger(
                    { s, severity, s2 ->
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
                    },
                    Logging.Severity.LS_VERBOSE,
                )
                .createInitializationOptions(),
        )
        return LibWebrtcInitialization
    }

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
        appContext: Context,
        closeableManager: CloseableManager,
        communicationWorkaround: CommunicationWorkaround,
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
                errorMessage: String?,
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
                errorMessage: String?,
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
                communicationWorkaround.onStartPlayout()
            }

            override fun onWebRtcAudioTrackStop() {
                LKLog.v { "Audio playout stops" }
                communicationWorkaround.onStopPlayout()
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
            // VOICE_COMMUNICATION needs to be used for echo cancelling.
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(audioOutputAttributes)

        moduleCustomizer?.invoke(builder)
        return builder.createAudioDeviceModule()
            .apply { closeableManager.registerClosable { release() } }
    }

    @Provides
    @Singleton
    fun eglBase(
        @Named(InjectionNames.OVERRIDE_EGL_BASE)
        @Nullable
        eglBaseOverride: EglBase?,
        memoryManager: CloseableManager,
    ): EglBase {
        return eglBaseOverride ?: EglBase
            .create()
            .apply { memoryManager.registerClosable { release() } }
    }

    @Provides
    fun eglContext(eglBase: EglBase): EglBase.Context = eglBase.eglBaseContext

    @Provides
    fun videoEncoderFactory(
        @Suppress("UNUSED_PARAMETER")
        @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
        webrtcInitialization: LibWebrtcInitialization,
        @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
        videoHwAccel: Boolean,
        eglContext: EglBase.Context,
        @Named(InjectionNames.OVERRIDE_VIDEO_ENCODER_FACTORY)
        @Nullable
        videoEncoderFactoryOverride: VideoEncoderFactory?,
    ): VideoEncoderFactory {
        return videoEncoderFactoryOverride ?: if (videoHwAccel) {
            CustomVideoEncoderFactory(
                eglContext,
                enableIntelVp8Encoder = true,
                enableH264HighProfile = false,
            )
        } else {
            SoftwareVideoEncoderFactory()
        }
    }

    @Provides
    @Singleton
    @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
    fun audioProcessingController(): AudioProcessingController {
        return CustomAudioProcessingFactory.sharedInstance()
    }

    @Provides
    fun videoDecoderFactory(
        @Suppress("UNUSED_PARAMETER")
        @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
        webrtcInitialization: LibWebrtcInitialization,
        @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
        videoHwAccel: Boolean,
        eglContext: EglBase.Context,
        @Named(InjectionNames.OVERRIDE_VIDEO_DECODER_FACTORY)
        @Nullable
        videoDecoderFactoryOverride: VideoDecoderFactory?,
    ): VideoDecoderFactory {
        return videoDecoderFactoryOverride ?: if (videoHwAccel) {
            CustomVideoDecoderFactory(eglContext)
        } else {
            SoftwareVideoDecoderFactory()
        }
    }

    @Provides
    @Singleton
    fun peerConnectionFactory(
        @Suppress("UNUSED_PARAMETER")
        @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
        webrtcInitialization: LibWebrtcInitialization,
        audioDeviceModule: AudioDeviceModule,
        videoEncoderFactory: VideoEncoderFactory,
        videoDecoderFactory: VideoDecoderFactory,
        memoryManager: CloseableManager,
    ): PeerConnectionFactory {
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setAudioProcessingFactory(CustomAudioProcessingFactory.sharedInstance().audioProcessingFactory())
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()
            .apply { memoryManager.registerClosable { dispose() } }
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

    @Provides
    fun sdpFactory() = SdpFactory.getInstance()
}

/**
 * @suppress
 */
object LibWebrtcInitialization
