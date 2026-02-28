/*
 * Copyright 2024-2026 LiveKit, Inc.
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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.util.LKLog
import livekit.org.webrtc.ScreenCapturerAndroid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val BUFFER_SIZE_FACTOR = 2
private const val MIN_GAIN_CHANGE = 0.01f
private const val DEFAULT_GAIN = 1f

private val DEFAULT_CONFIGURATOR: AudioPlaybackCaptureConfigurator = { builder ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        builder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
        builder.addMatchingUsage(AudioAttributes.USAGE_GAME)
    }
}

/**
 * A mixer for capturing screen share audio.
 *
 * Requires a media projection, which can be obtained from the screen share track.
 *
 * Additionally, for screen capture to work properly while your app is in the
 * background, a foreground service with the type `microphone` must be running.
 * Otherwise, audio capture will not return any audio data.
 *
 * Example usage:
 * ```
 * suspend fun startScreenCapture(data: Intent) {
 *     if (ActivityCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
 *         return
 *     }
 *
 *     // Publish the screen share video.
 *     room.localParticipant.setScreenShareEnabled(true, data)
 *
 *     // Optionally disable the mic for screenshare audio only
 *     // val javaAudioDeviceModule = (room.lkObjects.audioDeviceModule as? JavaAudioDeviceModule)
 *     // javaAudioDeviceModule?.setAudioRecordEnabled(false)
 *
 *     // Publish the audio track.
 *     room.localParticipant.setMicrophoneEnabled(true)
 *     val screenCaptureTrack = room.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalVideoTrack ?: return
 *     val audioTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack ?: return
 *
 *     // Start capturing the screen share audio.
 *     val audioCapturer = ScreenAudioCapturer.createFromScreenShareTrack(screenCaptureTrack) ?: return
 *     audioCapturer.gain = 0.1f // Lower the volume so that mic can still be heard clearly.
 *     audioTrack.setAudioBufferCallback(audioCapturer)
 * }
 *
 * suspend fun stopScreenCapture() {
 *     (room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack)
 *         ?.setAudioBufferCallback(null)
 *     room.localParticipant.setMicrophoneEnabled(false)
 *     room.localParticipant.setScreenShareEnabled(false)
 *
 *     // Remember to release when done capturing.
 *     audioCapturer?.releaseAudioResources()
 * }
 * ```
 */
@RequiresApi(Build.VERSION_CODES.Q)
class ScreenAudioCapturer
@RequiresPermission(Manifest.permission.RECORD_AUDIO)
constructor(
    private val mediaProjection: MediaProjection,
    /**
     * Screen share audio capture requires the use of [AudioPlaybackCaptureConfiguration].
     * This parameter allows customizing the configuration used. Note that
     * the configuration must have at least one match rule applied to it or
     * an exception will be thrown.
     *
     * The default configurator adds matching rules against all available usage
     * types that can be captured.
     */
    private val captureConfigurator: AudioPlaybackCaptureConfigurator = DEFAULT_CONFIGURATOR,
    private val context: Context? = null,
) : MixerAudioBufferCallback() {
    private var audioRecord: AudioRecord? = null
    private var audioRecordDevice: AudioRecord? = null

    private var hasInitialized = false
    private var byteBuffer: ByteBuffer? = null

    /**
     * Is AudioPlaybackCapture being used?
     */
    private var usePlaybackCapture = true

    /**
     * A multiplier to adjust the volume of the captured audio data.
     *
     * Values above 1 will increase the volume, values less than 1 will decrease it.
     */
    var gain = DEFAULT_GAIN

    /**
     * The last time the sound was not muted (nanoseconds), -1 indicates that there is sound currently.
     */
    private var silentSinceTime: Long = -1L

    /**
     * 600ms delay
     */
    private val SILENT_DURATION_THRESHOLD_NS = 600_000_000L

    /**
     * Should I reset the recording settings
     */
    private var isResetConfig = false

    /**
     * Threshold for audio detection system
     */
    private val SILENCE_ENERGY_THRESHOLD = 50L

    private val audioManager: AudioManager = context?.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * The system detects whether audio is playing.
     */
    fun isAudioPlaying(): Boolean {
        return audioManager.isMusicActive
    }

    override fun onBufferRequest(originalBuffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): BufferResponse? {
        if (!hasInitialized && audioRecord == null) {
            hasInitialized = true
            initAudioRecord(audioFormat = audioFormat, channelCount = channelCount, sampleRate = sampleRate)
        }
        val audioRecord = this.audioRecord ?: return null
        val recordBuffer = this.byteBuffer ?: return null
        audioRecord.read(recordBuffer, recordBuffer.capacity())
        var energy: Long? = null
        if (abs(gain - DEFAULT_GAIN) > MIN_GAIN_CHANGE) {
            recordBuffer.position(0)
            when (audioFormat) {
                AudioFormat.ENCODING_PCM_8BIT -> {
                    energy = adjustByteBuffer(recordBuffer, gain)
                }

                AudioFormat.ENCODING_PCM_16BIT,
                AudioFormat.ENCODING_DEFAULT,
                -> {
                    energy = adjustShortBuffer(recordBuffer.asShortBuffer(), gain)
                }

                AudioFormat.ENCODING_PCM_FLOAT -> {
                    energy = adjustFloatBuffer(recordBuffer.asFloatBuffer(), gain)
                }

                else -> {
                    LKLog.w { "Unsupported audio format: $audioFormat" }
                }
            }

            /**
             * When sharing the screen, system sound is detected. This is done because some phones previously ran Android versions below 10 and were upgraded to Android 10 or higher.
             * In Android 10 and higher, audio capture is set using `setAudioPlaybackCaptureConfig(audioCaptureConfig)`. However, some phones (e.g., Xiaomi 8 upgraded to Android 10)
             * are not compatible, causing system sound to fail to share after screen sharing even after setting `setAudioPlaybackCaptureConfig()`. To solve this problem, system sound is detected.
             * If system sound is present, and the recording returns a capability value <50 after a 600ms delay, it indicates a problem with the shared system audio. In this case, the recording configuration should be switched so that `setAudioPlaybackCaptureConfig(audioCaptureConfig)` is not set.
             */
            if (isAudioPlaying() && !isResetConfig && usePlaybackCapture) {
                energy?.let { currentEnergy ->
                    if (currentEnergy <= SILENCE_ENERGY_THRESHOLD) {
                        handleSilenceState()
                    } else {
                        handleNonSilenceState()
                    }
                }
            } else {
                // The mute timer has been reset.
                silentSinceTime = -1L
            }
        }
        return BufferResponse(recordBuffer)
    }

    /**
     * If the screen-sharing system has sound and has been silent for more than 600ms, and if recorded audio data is detected,
     * the recording configuration will be switched.
     */
    private fun handleSilenceState() {
        val now = System.nanoTime()
        if (silentSinceTime == -1L) {
            // Initially muted
            silentSinceTime = now
        } else if (now - silentSinceTime >= SILENT_DURATION_THRESHOLD_NS) {
            // If the system has audio sound but no audio data is detected, it indicates a problem with audio sharing in the screen sharing system. Perform a switch to release recording and reset the relevant values.
            releaseAudio()
            silentSinceTime = -1L
        }
    }

    /**
     * Release recording and reset related parameter values
     */
    private fun releaseAudio() {
        isResetConfig = true
        releaseAudioResources()
        usePlaybackCapture = false
        hasInitialized = false
    }

    /**
     * If no sound is detected, assign a value of -1
     */
    private fun handleNonSilenceState() {
        silentSinceTime = -1L
    }

    @SuppressLint("MissingPermission")
    fun initAudioRecord(audioFormat: Int, channelCount: Int, sampleRate: Int): Boolean {
        val audioCaptureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .apply(captureConfigurator)
            .build()
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("minBuffer size error: $minBufferSize")
        }
        LKLog.v { "AudioRecord.getMinBufferSize: $minBufferSize" }
        val bytesPerFrame = channelCount * getBytesPerSample(audioFormat)
        val framesPerBuffer = sampleRate / 100
        val readBufferCapacity = bytesPerFrame * framesPerBuffer
        val byteBuffer = ByteBuffer.allocateDirect(readBufferCapacity)
            .order(ByteOrder.nativeOrder())

        if (!byteBuffer.hasArray()) {
            LKLog.e { "ByteBuffer does not have backing array." }
            return false
        }

        this.byteBuffer = byteBuffer
        val bufferSizeInBytes: Int = max(BUFFER_SIZE_FACTOR * minBufferSize, readBufferCapacity)

        val audioRecordBuild = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSizeInBytes)

        // By default, Android 10 uses the `setAudioPlaybackCaptureConfig` setting.
        if (usePlaybackCapture) {
            audioRecordBuild.setAudioPlaybackCaptureConfig(audioCaptureConfig)
        }

        val audioRecord = audioRecordBuild.build()
        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            LKLog.e(e) { "AudioRecord.startRecording failed:" }
            audioRecord.release()
            return false
        }

        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            LKLog.e {
                "AudioRecord.startRecording failed - incorrect state: ${audioRecord.recordingState}"
            }
            return false
        }

        this.audioRecord = audioRecord

        return true
    }

    /**
     * Release any audio resources associated with this capturer.
     * This is not managed by LiveKit, so you must call this function
     * when finished to prevent memory leaks.
     */
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
            else -> throw IllegalArgumentException("Bad audio format $audioFormat")
        }
    }

    companion object {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        fun createFromScreenShareTrack(track: Track?, context: Context): ScreenAudioCapturer? {
            val screenShareTrack = track as? LocalVideoTrack

            if (screenShareTrack == null) {
                LKLog.e { "Tried to create screen audio capturer but passed track is not a video track: $track" }
                return null
            }

            val capturer = screenShareTrack.capturer as? ScreenCapturerAndroid

            if (capturer == null) {
                LKLog.e { "Tried to create screen audio capturer but passed track does not contain a screen capturer: ${screenShareTrack.capturer}" }
                return null
            }
            val mediaProjection = capturer.mediaProjection

            if (mediaProjection == null) {
                LKLog.e { "Tried to create screen audio capturer but the capturer doesn't have a media projection. Have you called startCapture?" }
                return null
            }

            return ScreenAudioCapturer(mediaProjection, context = context)
        }
    }

    private fun adjustByteBuffer(
        buffer: ByteBuffer,
        gain: Float,
    ): Long {
        var energy = 0L
        for (i in 0 until buffer.capacity()) {
            val sample = buffer[i].toInt() // Obtain PCM data
            // PCM Detection: Energy Calculation
            energy += sample * sample
            val adjusted = (buffer[i] * gain)
                .roundToInt()
                .coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt())
            buffer.put(i, adjusted.toByte())
        }
        return energy
    }

    private fun adjustShortBuffer(
        buffer: ShortBuffer,
        gain: Float,
    ): Long {
        var energy = 0L
        for (i in 0 until buffer.capacity()) {
            val sample = buffer[i].toInt() // Obtain PCM data
            // PCM Detection: Energy Calculation
            energy += sample * sample
            val adjusted = (buffer[i] * gain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.put(i, adjusted.toShort())
        }
        return energy
    }

    private fun adjustFloatBuffer(
        buffer: FloatBuffer,
        gain: Float,
    ): Long {
        var energy = 0L
        for (i in 0 until buffer.capacity()) {
            val sample = buffer[i].toInt() // Obtain PCM data
            // PCM Detection: Energy Calculation
            energy += sample * sample
            val adjusted = (buffer[i] * gain)
                .coerceIn(-1f, 1f)
            buffer.put(i, adjusted)
        }
        return energy
    }
}

typealias AudioPlaybackCaptureConfigurator = (AudioPlaybackCaptureConfiguration.Builder) -> Unit
