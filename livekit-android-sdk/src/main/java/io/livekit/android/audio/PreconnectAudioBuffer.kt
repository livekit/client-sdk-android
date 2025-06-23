/*
 * Copyright 2025 LiveKit, Inc.
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

import android.os.SystemClock
import io.livekit.android.audio.PreconnectAudioBuffer.Companion.DEFAULT_TOPIC
import io.livekit.android.audio.PreconnectAudioBuffer.Companion.TIMEOUT
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.ConnectionState
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamBytesOptions
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.LKLog
import io.livekit.android.util.flow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import livekit.org.webrtc.AudioTrackSink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class PreconnectAudioBuffer
internal constructor(timeout: Duration) : AudioTrackSink {

    companion object {
        const val DEFAULT_TOPIC = "lk.agent.pre-connect-audio-buffer"
        val TIMEOUT = 10.seconds
    }

    private val outputStreamLock = Any()
    private val outputStream by lazy {
        ByteArrayOutputStream()
    }

    private lateinit var collectedBytes: ByteArray

    private val tempArray = ByteArray(1024)
    private var initialTime = -1L

    private var bitsPerSample = 16
    private var sampleRate = 48000 // default sampleRate from JavaAudioDeviceModule
    private var numberOfChannels = 1 // default channels from JavaAudioDeviceModule

    private var isRecording = true
    private val timeoutMs = timeout.inWholeMilliseconds

    fun startRecording() {
        isRecording = true
    }

    fun stopRecording() {
        synchronized(outputStreamLock) {
            if (isRecording) {
                collectedBytes = outputStream.toByteArray()
                isRecording = false
            }
        }
    }

    fun clear() {
        stopRecording()
        collectedBytes = ByteArray(0)
    }

    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long,
    ) {
        if (!isRecording) {
            return
        }
        if (initialTime == -1L) {
            initialTime = SystemClock.elapsedRealtime()
        }

        this.bitsPerSample = bitsPerSample
        this.sampleRate = sampleRate
        this.numberOfChannels = numberOfChannels
        val currentTime = SystemClock.elapsedRealtime()
        // Limit reached, don't buffer any more.
        if (currentTime - initialTime > timeoutMs) {
            return
        }

        audioData.rewind()

        synchronized(outputStreamLock) {
            if (audioData.hasArray()) {
                outputStream.write(audioData.array(), audioData.arrayOffset(), audioData.capacity())
            } else {
                while (audioData.hasRemaining()) {
                    val readBytes = min(tempArray.size, audioData.remaining())
                    audioData.get(tempArray, 0, readBytes)
                    outputStream.write(tempArray)
                }
            }
        }
    }

    suspend fun sendAudioData(room: Room, trackSid: String?, agentIdentities: List<Participant.Identity>, topic: String = DEFAULT_TOPIC) {
        if (agentIdentities.isEmpty()) {
            return
        }

        val audioData = outputStream.toByteArray()
        if (audioData.size <= 1024) {
            LKLog.i { "Audio data size too small, nothing to send." }
            return
        }

        val sender = room.localParticipant.streamBytes(
            StreamBytesOptions(
                topic = topic,
                attributes = mapOf(
                    "sampleRate" to "${this.sampleRate}",
                    "channels" to "${this.numberOfChannels}",
                    "trackId" to (trackSid ?: ""),
                ),
                destinationIdentities = agentIdentities,
                totalSize = audioData.size.toLong(),
                name = "preconnect-audio-buffer",
            ),
        )

        try {
            val result = sender.write(audioData)
            if (result.isFailure) {
                result.exceptionOrNull()?.let { throw it }
            }
            sender.close()
        } catch (e: Exception) {
            sender.close(e.localizedMessage)
        }

        val samples = audioData.size / (numberOfChannels * bitsPerSample / 8)
        val duration = samples.toFloat() / sampleRate
        LKLog.i { "Sent ${duration}s (${audioData.size / 1024}KB) of audio data to ${agentIdentities.size} agent(s) (${agentIdentities.joinToString(",")})" }
    }
}

/**
 * Starts a pre-connect audio recording that will be sent to
 * any agents that connect within the [timeout]. This speeds up
 * preceived connection times, as the user can start speaking
 * prior to actual connection with the agent.
 *
 * This will automatically be cleaned up when the room disconnects or the operation fails.
 *
 * Example:
 * ```
 * try {
 *     room.withPreconnectAudio {
 *         // Audio is being captured automatically
 *         // Perform any other (async) setup here
 *         val (url, token) = tokenService.fetchConnectionDetails()
 *         room.connect(
 *             url = url,
 *             token = token,
 *         )
 *         room.localParticipant.setMicrophoneEnabled(true)
 *     }
 * } catch (e: Throwable) {
 *     Log.e(TAG, "Error!")
 * }
 * ```
 * @param timeout the timeout for the remote participant to subscribe to the audio track.
 * The room connection needs to be established and the remote participant needs to subscribe to the audio track
 * before the timeout is reached. Otherwise, the audio stream will be flushed without sending.
 * @param topic the topic to send the preconnect audio buffer to. By default this is configured for
 * use with LiveKit Agents.
 * @param onError The error handler to call when an error occurs while sending the audio buffer.
 * @param operation The connection lambda to call with the pre-connect audio.
 *
 */
suspend fun <T> Room.withPreconnectAudio(
    timeout: Duration = TIMEOUT,
    topic: String = DEFAULT_TOPIC,
    onError: ((e: Exception) -> Unit)? = null,
    operation: suspend () -> T,
) = coroutineScope {
    isPrerecording = true
    val audioTrack = localParticipant.getOrCreateDefaultAudioTrack()
    val preconnectAudioBuffer = PreconnectAudioBuffer(timeout)

    LKLog.v { "Starting preconnect audio buffer" }
    preconnectAudioBuffer.startRecording()
    audioTrack.addSink(preconnectAudioBuffer)
    audioTrack.prewarm()

    fun stopRecording() {
        if (!isPrerecording) {
            return
        }

        LKLog.v { "Stopping preconnect audio buffer" }
        audioTrack.removeSink(preconnectAudioBuffer)
        preconnectAudioBuffer.stopRecording()
        isPrerecording = false
    }

    // Clear the preconnect audio buffer after the timeout to free memory.
    launch {
        delay(TIMEOUT)
        preconnectAudioBuffer.clear()
    }

    val sentIdentities = mutableSetOf<Participant.Identity>()
    launch {
        suspend fun handleSendIfNeeded(participant: Participant) {
            coroutineScope inner@{
                engine::connectionState.flow
                    .takeWhile { it != ConnectionState.CONNECTED }
                    .collect()
                val kind = participant.kind
                val state = participant.state
                val identity = participant.identity
                if (sentIdentities.contains(identity) || kind != Participant.Kind.AGENT || state != Participant.State.ACTIVE || identity == null) {
                    return@inner
                }

                stopRecording()
                launch {
                    try {
                        preconnectAudioBuffer.sendAudioData(
                            room = this@withPreconnectAudio,
                            trackSid = audioTrack.sid,
                            agentIdentities = listOf(identity),
                            topic = topic,
                        )
                        sentIdentities.add(identity)
                    } catch (e: Exception) {
                        LKLog.w(e) { "Error occurred while sending the audio preconnect data." }
                        onError?.invoke(e)
                    }
                }
            }
        }

        events.collect { event ->
            when (event) {
                is RoomEvent.LocalTrackSubscribed -> {
                    LKLog.i { "Local audio track has been subscribed to, stopping preconnect audio recording." }
                    stopRecording()
                }

                is RoomEvent.ParticipantConnected -> {
                    // agents may connect with ACTIVE state and not trigger a participant state changed.
                    handleSendIfNeeded(event.participant)
                }

                is RoomEvent.ParticipantStateChanged -> {
                    handleSendIfNeeded(event.participant)
                }

                is RoomEvent.Disconnected -> {
                    cancel()
                }

                else -> {
                    // Intentionally blank.
                }
            }
        }
    }

    val retValue: T
    try {
        retValue = operation.invoke()
    } catch (e: Exception) {
        cancel()
        throw e
    }

    return@coroutineScope retValue
}
