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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AudioBufferCallbackDispatcherTest {

    @Test
    fun callsThrough() {
        val dispatcher = AudioBufferCallbackDispatcher()
        val audioBuffer = ByteBuffer.allocateDirect(0)
        var called = false
        val callback = object : AudioBufferCallback {
            override fun onBuffer(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): Long {
                assertEquals(audioBuffer, buffer)
                called = true
                return captureTimeNs
            }
        }
        dispatcher.bufferCallback = callback
        dispatcher.onBuffer(
            buffer = audioBuffer,
            audioFormat = 0,
            channelCount = 1,
            sampleRate = 48000,
            bytesRead = 0,
            captureTimeNs = 0L,
        )

        assertTrue(called)
    }
}
