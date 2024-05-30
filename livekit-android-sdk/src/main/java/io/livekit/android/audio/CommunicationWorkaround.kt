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
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.LKLog
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * @see CommunicationWorkaroundImpl
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
 */
@Singleton
@RequiresApi(Build.VERSION_CODES.R)
internal class CommunicationWorkaroundImpl
@Inject
constructor(
    @Named(InjectionNames.DISPATCHER_MAIN)
    dispatcher: MainCoroutineDispatcher,
) : CommunicationWorkaround {

    private val coroutineScope = CloseableCoroutineScope(dispatcher)
    private val started = MutableStateFlow(false)
    private val playoutStopped = MutableStateFlow(true)

    private var audioTrack: AudioTrack? = null

    private val isAudioTrackStarted = AtomicBoolean(false)

    init {
        coroutineScope.launch {
            started.combine(playoutStopped) { a, b -> a to b }
                .distinctUntilChanged()
                .collectLatest { (started, playoutStopped) ->
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

    override fun dispose() {
        coroutineScope.close()

        stop()

        audioTrack?.let { track ->
            synchronized(track) {
                track.release()
            }
        }
    }

    private fun onStateChanged(started: Boolean, playoutStopped: Boolean) {
        if (started && playoutStopped) {
            playAudioTrackIfNeeded()
        } else {
            pauseAudioTrackIfNeeded()
        }
    }

    @SuppressLint("Range")
    private fun buildAudioTrack(): AudioTrack {
        val audioSample = ByteBuffer.allocateDirect(getBytesPerSample(AUDIO_FORMAT) * AUDIO_FRAME_PER_BUFFER)

        return AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setBufferSizeInBytes(audioSample.capacity())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
            .apply {
                write(audioSample, audioSample.remaining(), AudioTrack.WRITE_BLOCKING)
                setLoopPoints(0, AUDIO_FRAME_PER_BUFFER - 1, -1)
            }
    }

    private fun playAudioTrackIfNeeded() {
        val swapped = isAudioTrackStarted.compareAndSet(false, true)
        if (!swapped) {
            // Already playing, nothing to do
            return
        }

        val audioTrack = audioTrack ?: buildAudioTrack().also { audioTrack = it }
        synchronized(audioTrack) {
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack.play()
            } else {
                LKLog.i { "Attempted to start communication workaround but track was not initialized." }
            }
        }
    }

    private fun pauseAudioTrackIfNeeded() {
        val swapped = isAudioTrackStarted.compareAndSet(true, false)
        if (!swapped) {
            // Already stopped, nothing to do
            return
        }

        audioTrack?.let { track ->
            synchronized(track) {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                } else {
                    LKLog.d { "Attempted to stop communication workaround but track was not initialized." }
                }
            }
        }
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

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_FRAME_PER_BUFFER = SAMPLE_RATE / 100 // 10 ms
    }
}
