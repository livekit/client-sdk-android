package io.livekit.android.mock

import livekit.LivekitModels

object TestData {

    val LOCAL_AUDIO_TRACK = with(LivekitModels.TrackInfo.newBuilder()) {
        sid = "local_audio_track_sid"
        type = LivekitModels.TrackType.AUDIO
        build()
    }

    val REMOTE_AUDIO_TRACK = with(LivekitModels.TrackInfo.newBuilder()) {
        sid = "remote_audio_track_sid"
        type = LivekitModels.TrackType.AUDIO
        build()
    }

    val LOCAL_PARTICIPANT = with(LivekitModels.ParticipantInfo.newBuilder()) {
        sid = "local_participant_sid"
        identity = "local_participant_identity"
        state = LivekitModels.ParticipantInfo.State.ACTIVE
        metadata = "local_metadata"
        permission = LivekitModels.ParticipantPermission.newBuilder()
            .setCanPublish(true)
            .setCanSubscribe(true)
            .setCanPublishData(true)
            .setHidden(true)
            .setRecorder(false)
            .build()
        build()
    }

    val REMOTE_PARTICIPANT = with(LivekitModels.ParticipantInfo.newBuilder()) {
        sid = "remote_participant_sid"
        identity = "remote_participant_identity"
        state = LivekitModels.ParticipantInfo.State.ACTIVE
        metadata = "remote_metadata"
        isPublisher = true
        permission = with(LivekitModels.ParticipantPermission.newBuilder()) {
            canPublish = true
            canSubscribe = true
            canPublishData
            build()
        }
        addTracks(REMOTE_AUDIO_TRACK)
        build()
    }


    val REMOTE_SPEAKER_INFO = with(LivekitModels.SpeakerInfo.newBuilder()) {
        sid = REMOTE_PARTICIPANT.sid
        level = 1.0f
        active = true
        build()
    }
}