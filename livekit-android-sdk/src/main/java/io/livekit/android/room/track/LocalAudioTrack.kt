package io.livekit.android.room.track

import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory

class LocalAudioTrack(
    name: String,
    audioOptions: AudioOptions? = null,
    rtcTrack: org.webrtc.AudioTrack
) : AudioTrack(name, rtcTrack) {
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
                factory.createAudioTrack("phone_audio_track_id", audioSource)
            rtcAudioTrack.setEnabled(true)

            return LocalAudioTrack(name = name, rtcTrack = rtcAudioTrack)
        }
    }
}