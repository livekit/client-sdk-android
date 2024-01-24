/*
 * Copyright 2024 LiveKit, Inc.
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

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.RequiresApi
import io.livekit.android.util.CloseableCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * @suppress
 */
interface CommunicationWorkaround {

    fun start()
    fun stop()
    fun onStartPlayout()
    fun onStopPlayout()

    fun dispose()
}

/**
 * @suppress
 */
class NoopCommunicationWorkaround
@Inject
constructor() : CommunicationWorkaround {
    override fun start() {
    }

    override fun stop() {
    }

    override fun onStartPlayout() {
    }

    override fun onStopPlayout() {
    }

    override fun dispose() {
    }
}

/**
 * Work around for communication mode resetting after 6 seconds if no audio playback or capture.
 * Issue only happens on 11+ (version code R).
 * https://issuetracker.google.com/issues/209493718
 *
 * @suppress
 */
@RequiresApi(Build.VERSION_CODES.R)
class CommunicationWorkaroundImpl
@Inject
constructor() : CommunicationWorkaround {

    private val coroutineScope = CloseableCoroutineScope(Dispatchers.Main)
    private val started = MutableStateFlow(false)
    private val playoutStopped = MutableStateFlow(false)

    private var audioTrack: AudioTrack? = null

    init {
        coroutineScope.launch {
            started.combine(playoutStopped) { a, b -> a to b }
                .distinctUntilChanged()
                .collect { (started, playoutStopped) ->
                    onStateChanged(started, playoutStopped)
                }
        }
    }

    override fun start() {
        started.value = true
    }

    override fun stop() {
        started.value = false
    }

    override fun onStartPlayout() {
        playoutStopped.value = false
    }

    override fun onStopPlayout() {
        playoutStopped.value = true
    }

    @SuppressLint("NewApi")
    private fun onStateChanged(started: Boolean, playoutStopped: Boolean) {
        if (started && playoutStopped) {
            startAudioTrackIfNeeded()
        } else {
            stopAudioTrackIfNeeded()
        }
    }

    @SuppressLint("Range")
    private fun startAudioTrackIfNeeded() {
        if (audioTrack != null) {
            return
        }
        val sampleRate = 16000
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bytesPerFrame = 1 * getBytesPerSample(audioFormat)
        val framesPerBuffer: Int = sampleRate / 100 // 10 ms
        val byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer)

        audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setBufferSizeInBytes(byteBuffer.capacity())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()

        audioTrack?.write(byteBuffer, byteBuffer.remaining(), AudioTrack.WRITE_BLOCKING)
        audioTrack?.setLoopPoints(0, framesPerBuffer - 1, -1)

        audioTrack?.play()
    }

    // Reference from Android code, AudioFormat.getBytesPerSample. BitPerSample / 8
    // Default audio data format is PCM 16 bits per sample.
    // Guaranteed to be supported by all devices
    private fun getBytesPerSample(audioFormat: Int): Int {
        return when (audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_IEC61937, AudioFormat.ENCODING_DEFAULT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_INVALID -> throw IllegalArgumentException("Bad audio format $audioFormat")
            else -> throw IllegalArgumentException("Bad audio format $audioFormat")
        }
    }

    private fun stopAudioTrackIfNeeded() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    override fun dispose() {
        coroutineScope.close()
        stop()
        stopAudioTrackIfNeeded()
    }
}
