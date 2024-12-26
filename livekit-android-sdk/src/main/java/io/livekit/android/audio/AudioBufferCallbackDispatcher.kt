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
import java.nio.ByteBuffer

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
