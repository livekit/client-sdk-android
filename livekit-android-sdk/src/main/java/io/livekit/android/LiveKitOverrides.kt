package io.livekit.android

import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.NoAudioHandler
import okhttp3.OkHttpClient
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Overrides to replace LiveKit internally used component with custom implementations.
 */
data class LiveKitOverrides(
    /**
     * Override the [OkHttpClient] used by the library.
     */
    val okHttpClient: OkHttpClient? = null,

    /**
     * Override the default [AudioDeviceModule].
     */
    val audioDeviceModule: AudioDeviceModule? = null,

    /**
     * Called after default setup to allow for customizations on the [JavaAudioDeviceModule].
     *
     * Not used if [audioDeviceModule] is provided.
     */
    val javaAudioDeviceModuleCustomizer: ((builder: JavaAudioDeviceModule.Builder) -> Unit)? = null,

    /**
     * Override the [VideoEncoderFactory] used by the library.
     */
    val videoEncoderFactory: VideoEncoderFactory? = null,

    /**
     * Override the [VideoDecoderFactory] used by the library.
     */
    val videoDecoderFactory: VideoDecoderFactory? = null,

    /**
     * Override the default [AudioHandler].
     *
     * Use [NoAudioHandler] to turn off automatic audio handling.
     */

    val audioHandler: AudioHandler? = null
)