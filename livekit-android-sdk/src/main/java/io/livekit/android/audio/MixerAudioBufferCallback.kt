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
import io.livekit.android.util.LKLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min

/**
 * A convenience class that handles mixing the microphone data and custom audio data.
 */
abstract class MixerAudioBufferCallback : AudioBufferCallback {

    class BufferResponse(
        /**
         * The byteBuffer to mix into the audio track.
         */
        val byteBuffer: ByteBuffer? = null,
        /**
         * The capture time stamp in nanoseconds, or null if not available.
         */
        val captureTimeNs: Long? = null,
    )

    final override fun onBuffer(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): Long {
        val response = onBufferRequest(buffer, audioFormat, channelCount, sampleRate, bytesRead, captureTimeNs)

        val customAudioBuffer = response?.byteBuffer

        if (customAudioBuffer != null) {
            buffer.order(ByteOrder.nativeOrder()).position(0)
            customAudioBuffer.order(ByteOrder.nativeOrder()).position(0)

            when (audioFormat) {
                AudioFormat.ENCODING_PCM_8BIT -> {
                    mixByteBuffers(original = buffer, customAudioBuffer)
                }

                AudioFormat.ENCODING_PCM_16BIT,
                AudioFormat.ENCODING_DEFAULT,
                -> {
                    mixShortBuffers(original = buffer.asShortBuffer(), customAudioBuffer.asShortBuffer())
                }

                AudioFormat.ENCODING_PCM_FLOAT -> {
                    mixFloatBuffers(original = buffer.asFloatBuffer(), customAudioBuffer.asFloatBuffer())
                }

                else -> {
                    LKLog.w { "Unsupported audio format: $audioFormat" }
                }
            }
        }

        val mixedCaptureTime = if (captureTimeNs != 0L) {
            captureTimeNs
        } else {
            response?.captureTimeNs ?: 0L
        }

        return mixedCaptureTime
    }

    abstract fun onBufferRequest(originalBuffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): BufferResponse?

    private fun mixByteBuffers(
        original: ByteBuffer,
        addBuffer: ByteBuffer,
    ) {
        val size = min(original.capacity(), addBuffer.capacity())
        if (size <= 0) return
        for (i in 0 until size) {
            val sum = (original[i].toInt() + addBuffer[i].toInt())
                .coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt())
            original.put(i, sum.toByte())
        }
    }

    private fun mixShortBuffers(
        original: ShortBuffer,
        addBuffer: ShortBuffer,
    ) {
        val size = min(original.capacity(), addBuffer.capacity())
        if (size <= 0) return

        for (i in 0 until size) {
            val sum = (original[i].toInt() + addBuffer[i].toInt())
                .coerceIn(
                    minimumValue = Short.MIN_VALUE.toInt(),
                    maximumValue = Short.MAX_VALUE.toInt(),
                )
            original.put(i, sum.toShort())
        }
    }

    private fun mixFloatBuffers(
        original: FloatBuffer,
        addBuffer: FloatBuffer,
    ) {
        val size = min(original.capacity(), addBuffer.capacity())
        if (size <= 0) return
        for (i in 0 until size) {
            val sum = (original[i] + addBuffer[i])
                .coerceIn(-1f, 1f)
            original.put(i, sum)
        }
    }
}
