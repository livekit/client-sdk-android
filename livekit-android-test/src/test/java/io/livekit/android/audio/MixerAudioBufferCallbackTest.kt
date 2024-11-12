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
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MixerAudioBufferCallbackTest {

    @Test
    fun mixesByte() {
        val mixer = IncrementMixer()
        val buffer = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
        buffer.put(0, 0.toByte())
        mixer.onBuffer(
            buffer = buffer,
            audioFormat = AudioFormat.ENCODING_PCM_8BIT,
            channelCount = 1,
            sampleRate = 1,
            bytesRead = 1,
            captureTimeNs = 0,
        )

        assertEquals((0 + INCREMENT).toByte(), buffer.get(0))
    }

    @Test
    fun mixesShort() {
        val mixer = IncrementMixer()
        val buffer = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder())
        val shortBuffer = buffer.asShortBuffer()
        shortBuffer.put(0, 0.toShort())
        mixer.onBuffer(
            buffer = buffer,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            channelCount = 1,
            sampleRate = 1,
            bytesRead = 2,
            captureTimeNs = 0,
        )

        assertEquals((0 + INCREMENT).toShort(), shortBuffer.get(0))
    }

    @Test
    fun mixesFloat() {
        val mixer = IncrementMixer()
        val buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        val floatBuffer = buffer.asFloatBuffer()
        floatBuffer.put(0, 0.toFloat())
        mixer.onBuffer(
            buffer = buffer,
            audioFormat = AudioFormat.ENCODING_PCM_FLOAT,
            channelCount = 1,
            sampleRate = 1,
            bytesRead = 1,
            captureTimeNs = 0,
        )

        assertEquals((0 + INCREMENT).toFloat(), floatBuffer.get(0))
    }

    companion object {
        const val INCREMENT = 1
    }

    class IncrementMixer : MixerAudioBufferCallback() {
        override fun onBufferRequest(originalBuffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): BufferResponse? {
            val byteBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

            when (audioFormat) {
                AudioFormat.ENCODING_PCM_8BIT -> {
                    byteBuffer.put(0, INCREMENT.toByte())
                }

                AudioFormat.ENCODING_PCM_16BIT -> {
                    byteBuffer.asShortBuffer().put(0, INCREMENT.toShort())
                }

                AudioFormat.ENCODING_PCM_FLOAT -> {
                    byteBuffer.asFloatBuffer().put(0, INCREMENT.toFloat())
                }

                AudioFormat.ENCODING_INVALID -> throw IllegalArgumentException("Bad audio format $audioFormat")
            }

            return BufferResponse(byteBuffer)
        }
    }
}

private fun getBytesPerSample(audioFormat: Int): Int {
    return when (audioFormat) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_IEC61937, AudioFormat.ENCODING_DEFAULT -> 2
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_INVALID -> throw IllegalArgumentException("Bad audio format $audioFormat")
        else -> throw IllegalArgumentException("Bad audio format $audioFormat")
    }
}
