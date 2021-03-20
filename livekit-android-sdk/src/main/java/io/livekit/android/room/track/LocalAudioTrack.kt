package io.livekit.android.room.track

import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import java.util.*

class LocalAudioTrack(
    name: String,
    audioOptions: AudioOptions? = null,
    rtcTrack: org.webrtc.AudioTrack
) : AudioTrack(name, rtcTrack) {
    var enabled: Boolean
        get() = rtcTrack.enabled()
        set(value) {
            rtcTrack.setEnabled(value)
        }

    var sid: Sid? = null
        internal set
    var audioOptions = audioOptions
        private set

    companion object {
        fun createTrack(
            factory: PeerConnectionFactory,
            audioConstraints: MediaConstraints,
            name: String = ""
        ): LocalAudioTrack {

            val audioSource = factory.createAudioSource(audioConstraints)
            val rtcAudioTrack =
                factory.createAudioTrack(UUID.randomUUID().toString(), audioSource)

            return LocalAudioTrack(name = name, rtcTrack = rtcAudioTrack)
        }
    }
}