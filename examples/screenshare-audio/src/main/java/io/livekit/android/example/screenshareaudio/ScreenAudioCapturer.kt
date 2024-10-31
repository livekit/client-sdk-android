package io.livekit.android.example.screenshareaudio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.github.ajalt.timberkt.Timber
import io.livekit.android.audio.MixerAudioBufferCallback
import java.nio.ByteBuffer

private const val BUFFER_SIZE_FACTOR = 2

class ScreenAudioCapturer(val mediaProjection: MediaProjection) : MixerAudioBufferCallback() {
    var hasInitialized = false
    var audioRecord: AudioRecord? = null
    var byteBuffer: ByteBuffer? = null


    override fun onBufferRequest(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): BufferResponse? {

        if (!hasInitialized && audioRecord == null) {
            hasInitialized = true
            initAudioRecord(audioFormat = audioFormat, channelCount = channelCount, sampleRate = sampleRate)
        }

        val audioRecord = this.audioRecord ?: return null
        val byteBuffer = this.byteBuffer ?: return null
        val bytesRead = audioRecord.read(byteBuffer, byteBuffer.capacity())

        byteBuffer.position(0)
        val shortBuffer = byteBuffer.asShortBuffer()
        var accum = 0f
        for (i in 0 until bytesRead / 2) {
            accum += shortBuffer[i]
        }
        accum /= bytesRead / 2f
        //Timber.e { "bytes read: $bytesRead, $accum" }
        return BufferResponse(byteBuffer)
    }

    @SuppressLint("MissingPermission")
    fun initAudioRecord(audioFormat: Int, channelCount: Int, sampleRate: Int) {
        val audioCapture = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .excludeUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("minBuffer size error: $minBufferSize")
        }
        Timber.i { "AudioRecord.getMinBufferSize: $minBufferSize" }


        val bytesPerFrame = channelCount * getBytesPerSample(audioFormat);
        val framesPerBuffer = sampleRate / 100
        byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer)

        val bufferSizeInBytes: Int = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, byteBuffer!!.capacity())

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSizeInBytes)
            .setAudioPlaybackCaptureConfig(audioCapture)
            .build()
        audioRecord!!.startRecording()
    }

    fun releaseAudioResources() {
        val audioRecord = this.audioRecord
        if (audioRecord != null) {
            audioRecord.release()
            this.audioRecord = null
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
}
