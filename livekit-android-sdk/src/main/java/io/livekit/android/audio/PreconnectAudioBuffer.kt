package io.livekit.android.audio

import android.os.SystemClock
import io.livekit.android.audio.PreconnectAudioBuffer.Companion.DEFAULT_TOPIC
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import livekit.org.webrtc.AudioTrackSink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

internal class PreconnectAudioBuffer
internal constructor() : AudioTrackSink {

    companion object {
        const val MAX_SIZE = 10 * 1024 * 1024 // 10 MB
        const val SAMPLE_RATE = 48000
        const val DEFAULT_TOPIC = "lk.agent.pre-connect-audio-buffer"
        val TIMEOUT = 10.seconds.inWholeMilliseconds

    }

    private val outputStreamLock = Any()
    private val outputStream by lazy {
        ByteArrayOutputStream()
    }

    private lateinit var collectedBytes: ByteArray

    private val tempArray = ByteArray(1024)
    private var initialTime = 0L

    private var bitsPerSample = 2
    private var sampleRate = SAMPLE_RATE
    private var numberOfChannels = 2

    private var isRecording = true

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
        if (initialTime == 0L) {
            initialTime = SystemClock.elapsedRealtime()
        }

        val currentTime = SystemClock.elapsedRealtime()
        // Limit reached, don't buffer any more.
        if (currentTime - initialTime > TIMEOUT) {
            return
        }

        audioData.rewind()

        synchronized(outputStreamLock) {
            if (audioData.hasArray()) {
                outputStream.write(audioData.array())
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
            sender.write(audioData)
            sender.close()
        } catch (e: Exception) {
            sender.close(e.localizedMessage)
        }

        LKLog.i { "Sent ${audioData.size / 1024}KB of audio data to ${agentIdentities.size} agent(s) (${agentIdentities.joinToString(",")})" }
    }
}

/**
 *
 */
suspend fun <T> Room.withPreconnectAudio(topic: String = DEFAULT_TOPIC, operation: suspend () -> T) = coroutineScope {

    isPrerecording = true
    val audioTrack = localParticipant.getOrCreateDefaultAudioTrack()
    val preconnectAudioBuffer = PreconnectAudioBuffer()

    LKLog.e { "starting preconnect buffer" }
    preconnectAudioBuffer.startRecording()
    audioTrack.addSink(preconnectAudioBuffer)
    audioTrack.prewarm()

    fun stopRecording() {
        audioTrack.removeSink(preconnectAudioBuffer)
        preconnectAudioBuffer.stopRecording()
        isPrerecording = false
    }

    val sentIdentities = mutableSetOf<Participant.Identity>()
    launch {
        suspend fun handleSendIfNeeded(participant: Participant) {
            coroutineScope {
                engine::connectionState.flow
                    .takeWhile { it != ConnectionState.CONNECTED }
                    .collect()
                val kind = participant.kind
                val state = participant.state
                val identity = participant.identity
                if (sentIdentities.contains(identity) || kind != Participant.Kind.AGENT || state != Participant.State.ACTIVE || identity == null) {
                    return@coroutineScope
                }

                LKLog.e { "stopping preconnect buffer" }
                stopRecording()
                launch {
                    preconnectAudioBuffer.sendAudioData(
                        room = this@withPreconnectAudio,
                        trackSid = audioTrack.sid,
                        agentIdentities = listOf(identity),
                        topic = topic,
                    )
                    sentIdentities.add(identity)
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
                    // agents may connect with ACTIVE state
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

    try {
        operation.invoke()
    } catch (e: Exception) {
        cancel()
        throw e
    }


}
