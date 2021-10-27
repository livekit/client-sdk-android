package io.livekit.android.dagger

import android.content.Context
import dagger.Module
import dagger.Provides
import io.livekit.android.util.LKLog
import io.livekit.android.webrtc.SimulcastVideoEncoderFactoryWrapper
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import javax.inject.Named
import javax.inject.Singleton


@Module
class RTCModule {
    companion object {
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
            eglContext: EglBase.Context
        ): VideoEncoderFactory {

            return if (videoHwAccel) {
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
}