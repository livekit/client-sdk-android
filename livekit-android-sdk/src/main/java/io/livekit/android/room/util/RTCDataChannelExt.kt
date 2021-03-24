package io.livekit.android.room.util

import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import org.webrtc.DataChannel

fun DataChannel.unpackedTrackLabel(): Triple<String, String, String> {
    val parts = label().split("|")
    val participantSid: String
    val trackSid: String
    val name: String

    if (parts.count() == 3) {
        participantSid = parts[0]
        trackSid = parts[1]
        name = parts[2]
    } else {
        participantSid = ""
        trackSid = ""
        name = ""
    }
    
    return Triple(participantSid, trackSid, name)
}