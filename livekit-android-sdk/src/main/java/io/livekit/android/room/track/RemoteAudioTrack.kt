package io.livekit.android.room.track

class RemoteAudioTrack(
    sid: Track.Sid,
    playbackEnabled: Boolean = true,
    name: String,
    rtcTrack: org.webrtc.AudioTrack
) : AudioTrack(name, rtcTrack), RemoteTrack {

    override var sid: Track.Sid = sid
    var playbackEnabled = playbackEnabled
        internal set


}