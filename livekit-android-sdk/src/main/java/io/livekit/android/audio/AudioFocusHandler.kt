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

package io.livekit.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A basic [AudioHandler] that manages audio focus while started.
 */
@Singleton
open class AudioFocusHandler
@Inject
constructor(context: Context) : AudioHandler {

    /**
     * The audio focus mode to use while started.
     *
     * Defaults to [AudioManager.AUDIOFOCUS_GAIN].
     */
    var focusMode: Int = AudioManager.AUDIOFOCUS_GAIN

    /**
     * The audio stream type to use when requesting audio focus on pre-O devices.
     *
     * Defaults to [AudioManager.STREAM_MUSIC].
     *
     * Refer to this [compatibility table](https://source.android.com/docs/core/audio/attributes#compatibility)
     * to ensure that your values match between android versions.
     */
    var audioStreamType: Int = AudioManager.STREAM_MUSIC

    /**
     * The audio attribute usage type to use when requesting audio focus on devices O and beyond.
     *
     * Defaults to [AudioAttributes.USAGE_MEDIA].
     *
     * Refer to this [compatibility table](https://source.android.com/docs/core/audio/attributes#compatibility)
     * to ensure that your values match between android versions.
     */
    var audioAttributeUsageType: Int = AudioAttributes.USAGE_MEDIA

    /**
     * The audio attribute content type to use when requesting audio focus on devices O and beyond.
     *
     * Defaults to [AudioAttributes.CONTENT_TYPE_SPEECH].
     *
     * Refer to this [compatibility table](https://source.android.com/docs/core/audio/attributes#compatibility)
     * to ensure that your values match between android versions.
     */
    var audioAttributeContentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRequest: AudioFocusRequest? = null
    private var audioFocusListener = AudioManager.OnAudioFocusChangeListener {
        onAudioFocusChangeListener?.onAudioFocusChange(it)
    }

    /**
     * Set this to listen to audio focus changes.
     */
    var onAudioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    override fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioRequest = createAudioRequest()
            audioRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(audioFocusListener, audioStreamType, focusMode)
        }
    }

    override fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(onAudioFocusChangeListener)
        }
    }

    /**
     * Creates the request used when requesting audio focus.
     *
     * The default implementation creates an audio focus request based on the
     * settings of this object.
     *
     * Only used on Android O and upwards. On lower platforms,
     * the request will be made using the [audioStreamType] and [focusMode]
     * settings.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    open fun createAudioRequest(): AudioFocusRequest {
        return AudioFocusRequest.Builder(focusMode)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(audioAttributeUsageType)
                    .setContentType(audioAttributeContentType)
                    .build(),
            )
            .build()
    }
}
