package io.livekit.android.audio

import android.os.SystemClock
import dagger.assisted.AssistedInject
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamBytesOptions
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.LKLog
import livekit.org.webrtc.AudioTrackSink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

internal class PreconnectAudioBuffer
@AssistedInject
internal constructor(
)
    : AudioTrackSink {

    companion object {
        const val MAX_SIZE = 10 * 1024 * 1024 // 10 MB
        const val SAMPLE_RATE = 48000
        val TIMEOUT = 10.seconds.inWholeMilliseconds
    }

    private val outputStream by lazy {
        ByteArrayOutputStream()
    }

    private val tempArray = ByteArray(1024)
    private var initialTime = 0L

    private var bitsPerSample = 2
    private var sampleRate = SAMPLE_RATE
    private var numberOfChannels = 2
    
    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long,
    ) {
        if(initialTime == 0L) {
            initialTime = SystemClock.elapsedRealtime()
        }

        val currentTime = SystemClock.elapsedRealtime()
        // Limit reached, don't buffer any more.
        if(currentTime - initialTime > TIMEOUT) {
            return
        }

        audioData.rewind()
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

    suspend fun sendAudioData(room: Room, agentIdentities: List<Participant.Identity>, topic: String) {
        if(agentIdentities.isEmpty()) {
            return
        }

        val audioData = outputStream.toByteArray()
        if(audioData.size <= 1024) {
            LKLog.i { "Audio data size too small, nothing to send." }
            return
        }

        val sender = room.localParticipant.streamBytes(
            StreamBytesOptions(
                topic = topic,
                attributes = mapOf(
                    "sampleRate" to "$SAMPLE_RATE",
                ),
                destinationIdentities = agentIdentities,
                totalSize = audioData.size.toLong(),
                name = "preconnect-audio-buffer",
            )
        )

        sender.write(audioData)
        sender.close()

        LKLog.i { "Sent ${audioData.size / 1024}KB of audio data to ${agentIdentities.size} agent(s) (${agentIdentities.joinToString { it.value + "," }}" }
    }
}
