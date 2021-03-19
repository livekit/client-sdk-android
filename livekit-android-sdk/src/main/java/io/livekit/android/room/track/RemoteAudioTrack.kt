package io.livekit.android.room.track

class RemoteAudioTrack(
    sid: Sid,
    playbackEnabled: Boolean = true,
    name: String,
    rtcTrack: org.webrtc.AudioTrack
) : AudioTrack(name, rtcTrack), RemoteTrack {


    override var sid: Sid = sid
    var playbackEnabled = playbackEnabled
        internal set


}