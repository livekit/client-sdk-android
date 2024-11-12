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
