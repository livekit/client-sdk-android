package io.livekit.android.room.track

class LocalAudioTrack(
    name: String,
    audioOptions: AudioOptions? = null,
    rtcTrack: org.webrtc.AudioTrack
) : AudioTrack(name, rtcTrack) {
    var sid: Sid? = null
        internal set
    var audioOptions = audioOptions
        private set
}