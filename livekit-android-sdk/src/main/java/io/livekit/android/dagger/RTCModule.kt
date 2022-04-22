package io.livekit.android.dagger

import android.content.Context
import androidx.annotation.Nullable
import dagger.Module
import dagger.Provides
import io.livekit.android.LiveKit
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import io.livekit.android.webrtc.SimulcastVideoEncoderFactoryWrapper
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber
import javax.inject.Named
import javax.inject.Singleton


@Module
object RTCModule {
    @Provides
    @Singleton
    fun audioModule(appContext: Context): AudioDeviceModule {

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
                LKLog.i { "Audio recording starts" }
            }

            override fun onWebRtcAudioRecordStop() {
                LKLog.i { "Audio recording stops" }
            }
        }

        // Set audio track state callbacks.
        val audioTrackStateCallback: JavaAudioDeviceModule.AudioTrackStateCallback = object :
            JavaAudioDeviceModule.AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                LKLog.i { "Audio playout starts" }
            }

            override fun onWebRtcAudioTrackStop() {
                LKLog.i { "Audio playout stops" }
            }
        }

        return JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioRecordErrorCallback(audioRecordErrorCallback)
            .setAudioTrackErrorCallback(audioTrackErrorCallback)
            .setAudioRecordStateCallback(audioRecordStateCallback)
            .setAudioTrackStateCallback(audioTrackStateCallback)
            .createAudioDeviceModule()
    }

    @Provides
    @Singleton
    fun eglBase(): EglBase {
        return EglBase.create()
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
                enableH264HighProfile = true,
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
    ): VideoDecoderFactory {
        return if (videoHwAccel) {
            DefaultVideoDecoderFactory(eglContext)
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
    @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
    fun videoHwAccel() = true
}