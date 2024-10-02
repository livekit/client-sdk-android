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

import android.media.AudioFormat
import android.os.SystemClock
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import livekit.org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback
import java.nio.ByteBuffer

class AudioRecordSamplesDispatcher : SamplesReadyCallback {

    private val sinks = mutableSetOf<AudioTrackSink>()

    @Synchronized
    fun registerSink(sink: AudioTrackSink) {
        sinks.add(sink)
    }

    @Synchronized
    fun unregisterSink(sink: AudioTrackSink) {
        sinks.remove(sink)
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

    @Synchronized
    override fun onWebRtcAudioRecordSamplesReady(samples: JavaAudioDeviceModule.AudioSamples) {
        val bitsPerSample = getBytesPerSample(samples.audioFormat) * 8
        val numFrames = samples.sampleRate / 100 // 10ms worth of samples.
        val timestamp = SystemClock.elapsedRealtime()
        for (sink in sinks) {
            val byteBuffer = ByteBuffer.wrap(samples.data)
            sink.onData(
                byteBuffer,
                bitsPerSample,
                samples.sampleRate,
                samples.channelCount,
                numFrames,
                timestamp,
            )
        }
    }
}
