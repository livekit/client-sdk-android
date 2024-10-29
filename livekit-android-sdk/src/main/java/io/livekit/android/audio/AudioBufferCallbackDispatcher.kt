package io.livekit.android.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min

/**
 * @suppress
 */
class AudioBufferCallbackDispatcher : livekit.org.webrtc.audio.JavaAudioDeviceModule.AudioBufferCallback {
    var bufferCallback: AudioBufferCallback? = null

    override fun onBuffer(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): Long {
        return bufferCallback?.onBuffer(
            buffer = buffer,
            audioFormat = audioFormat,
            channelCount = channelCount,
            sampleRate = sampleRate,
            bytesRead = bytesRead,
            captureTimeNs = captureTimeNs,
        ) ?: 0L
    }
}

interface AudioBufferCallback {
    /**
     * Called when new audio samples are ready.
     * @param buffer the buffer of audio bytes. Changes to this buffer will be published on the audio track.
     * @param audioFormat the audio encoding. See [AudioFormat.ENCODING_PCM_8BIT],
     * [AudioFormat.ENCODING_PCM_16BIT], and [AudioFormat.ENCODING_PCM_FLOAT]. Note
     * that [AudioFormat.ENCODING_DEFAULT] defaults to PCM-16bit.
     * @param channelCount
     * @param sampleRate
     * @param bytesRead the byte count originally read from the microphone.
     * @param captureTimeNs the capture timestamp of the original audio data in nanoseconds.
     * @return the capture timestamp in nanoseconds. Return 0 if not available.
     */
    fun onBuffer(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): Long
}

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
            buffer.position(0)
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
            }
        }

        val mixedCaptureTime = if (captureTimeNs != 0L) {
            captureTimeNs
        } else {
            response?.captureTimeNs ?: 0L
        }

        return mixedCaptureTime
    }

    abstract fun onBufferRequest(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): BufferResponse?

}

fun mixByteBuffers(
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

fun mixShortBuffers(
    original: ShortBuffer,
    addBuffer: ShortBuffer,
) {
    val size = min(original.capacity(), addBuffer.capacity())
    if (size <= 0) return
    for (i in 0 until size) {
        val sum = (original[i].toInt() + addBuffer[i].toInt())
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        original.put(i, sum.toShort())
    }
}

fun mixFloatBuffers(
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
