package io.livekit.android.room.util

import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import org.webrtc.DataChannel

fun DataChannel.unpackedTrackLabel(): Triple<Participant.Sid, Track.Sid, String> {
    val parts = label().split("|")
    val participantSid: Participant.Sid
    val trackSid: Track.Sid
    val name: String

    if (parts.count() == 3) {
        participantSid = Participant.Sid(parts[0])
        trackSid = Track.Sid(parts[1])
        name = parts[2]
    } else {
        participantSid = Participant.Sid("")
        trackSid = Track.Sid("")
        name = ""
    }
    
    return Triple(participantSid, trackSid, name)
}