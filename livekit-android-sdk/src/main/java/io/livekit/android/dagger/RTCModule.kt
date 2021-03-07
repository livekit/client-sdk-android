package io.livekit.android.dagger

import android.content.Context
import com.github.ajalt.timberkt.Timber
import dagger.Module
import dagger.Provides
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
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
                    Timber.e { "onWebRtcAudioRecordInitError: $errorMessage" }
                }

                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    errorMessage: String?
                ) {
                    Timber.e { "onWebRtcAudioRecordStartError: $errorCode. $errorMessage" }
                }

                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    Timber.e { "onWebRtcAudioRecordError: $errorMessage" }
                }
            }

            val audioTrackErrorCallback = object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    Timber.e { "onWebRtcAudioTrackInitError: $errorMessage" }
                }

                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                    errorMessage: String?
                ) {
                    Timber.e { "onWebRtcAudioTrackStartError: $errorCode. $errorMessage" }
                }

                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    Timber.e { "onWebRtcAudioTrackError: $errorMessage" }
                }

            }
            val audioRecordStateCallback: JavaAudioDeviceModule.AudioRecordStateCallback = object :
                JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    Timber.i { "Audio recording starts" }
                }

                override fun onWebRtcAudioRecordStop() {
                    Timber.i { "Audio recording stops" }
                }
            }

            // Set audio track state callbacks.
            val audioTrackStateCallback: JavaAudioDeviceModule.AudioTrackStateCallback = object :
                JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() {
                    Timber.i { "Audio playout starts" }
                }

                override fun onWebRtcAudioTrackStop() {
                    Timber.i { "Audio playout stops" }
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
        fun peerConnectionFactory(
            appContext: Context,
            audioDeviceModule: AudioDeviceModule
        ): PeerConnectionFactory {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions()
            )

            return PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()
        }
    }
}