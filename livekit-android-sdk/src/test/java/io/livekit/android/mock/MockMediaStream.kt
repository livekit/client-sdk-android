package io.livekit.android.mock

import org.webrtc.AudioTrack
import org.webrtc.MediaStream
import org.webrtc.VideoTrack

fun createMediaStreamId(participantSid: String, trackSid: String) =
    "${TestData.REMOTE_PARTICIPANT.sid}|${TestData.REMOTE_AUDIO_TRACK.sid}"

class MockMediaStream(private val id: String = "id") : MediaStream(1L) {

    override fun addTrack(track: AudioTrack): Boolean {
        return audioTracks.add(track)
    }

    override fun addTrack(track: VideoTrack?): Boolean {
        return videoTracks.add(track)
    }

    override fun addPreservedTrack(track: VideoTrack?): Boolean {
        return preservedVideoTracks.add(track)
    }

    override fun removeTrack(track: AudioTrack?): Boolean {
        return audioTracks.remove(track)
    }

    override fun removeTrack(track: VideoTrack?): Boolean {
        return videoTracks.remove(track)
    }

    override fun dispose() {
        // Don't do anything in this stubbed class
    }

    override fun getId(): String = id
}