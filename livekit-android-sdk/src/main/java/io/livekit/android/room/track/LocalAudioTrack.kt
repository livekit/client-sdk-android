package io.livekit.android.room.track

import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import java.util.*

/**
 * Represents a local audio track (generally using the microphone as input).
 *
 * This class should not be constructed directly, but rather through [LocalParticipant]
 */
class LocalAudioTrack(
    name: String,
    mediaTrack: org.webrtc.AudioTrack
) : AudioTrack(name, mediaTrack) {
    var enabled: Boolean
        get() = rtcTrack.enabled()
        set(value) {
            rtcTrack.setEnabled(value)
        }

    companion object {
        internal fun createTrack(
            factory: PeerConnectionFactory,
            audioConstraints: MediaConstraints = MediaConstraints(),
            name: String = ""
        ): LocalAudioTrack {
            val audioSource = factory.createAudioSource(audioConstraints)
            val rtcAudioTrack =
                factory.createAudioTrack(UUID.randomUUID().toString(), audioSource)

            return LocalAudioTrack(name = name, mediaTrack = rtcAudioTrack)
        }
    }
}
