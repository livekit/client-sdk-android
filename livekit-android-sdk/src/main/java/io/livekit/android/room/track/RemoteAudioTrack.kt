package io.livekit.android.room.track

class RemoteAudioTrack(
    sid: String,
    playbackEnabled: Boolean = true,
    name: String,
    mediaTrack: org.webrtc.AudioTrack
) : AudioTrack(name, mediaTrack), RemoteTrack {

    override var sid: String = sid
    var playbackEnabled = playbackEnabled
        internal set

}