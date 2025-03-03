/*
 * Copyright 2023-2025 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.test.mock

import livekit.LivekitModels
import livekit.LivekitRtc
import java.util.UUID

object TestData {

    const val EXAMPLE_URL = "ws://www.example.com"

    val LOCAL_AUDIO_TRACK = with(LivekitModels.TrackInfo.newBuilder()) {
        sid = "TR_local_audio_track_sid"
        type = LivekitModels.TrackType.AUDIO
        source = LivekitModels.TrackSource.MICROPHONE
        build()
    }
    val LOCAL_VIDEO_TRACK = with(LivekitModels.TrackInfo.newBuilder()) {
        sid = "TR_local_video_track_sid"
        type = LivekitModels.TrackType.VIDEO
        source = LivekitModels.TrackSource.CAMERA
        build()
    }

    val REMOTE_AUDIO_TRACK = with(LivekitModels.TrackInfo.newBuilder()) {
        sid = "TR_remote_audio_track_sid"
        type = LivekitModels.TrackType.AUDIO
        source = LivekitModels.TrackSource.MICROPHONE
        build()
    }

    val REMOTE_VIDEO_TRACK = with(LivekitModels.TrackInfo.newBuilder()) {
        sid = "TR_remote_video_track_sid"
        type = LivekitModels.TrackType.VIDEO
        source = LivekitModels.TrackSource.CAMERA
        build()
    }

    val LOCAL_PARTICIPANT = with(LivekitModels.ParticipantInfo.newBuilder()) {
        sid = "local_participant_sid"
        identity = "local_participant_identity"
        state = LivekitModels.ParticipantInfo.State.ACTIVE
        metadata = "local_metadata"
        permission = with(LivekitModels.ParticipantPermission.newBuilder()) {
            canPublish = true
            canSubscribe = true
            canPublishData = true

            hidden = false
            recorder = false
            build()
        }
        putAttributes("attribute", "value")
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
        addTracks(REMOTE_VIDEO_TRACK)
        putAttributes("attribute", "value")
        build()
    }

    val REMOTE_SPEAKER_INFO = with(LivekitModels.SpeakerInfo.newBuilder()) {
        sid = REMOTE_PARTICIPANT.sid
        level = 1.0f
        active = true
        build()
    }

    // Signal Responses
    // /////////////////////////////////

    val JOIN = with(LivekitRtc.SignalResponse.newBuilder()) {
        join = with(LivekitRtc.JoinResponse.newBuilder()) {
            room = with(LivekitModels.Room.newBuilder()) {
                name = "roomname"
                build()
            }
            participant = LOCAL_PARTICIPANT
            subscriberPrimary = true
            addIceServers(
                with(LivekitRtc.ICEServer.newBuilder()) {
                    addUrls("stun:stun.join.com:19302")
                    username = "username"
                    credential = "credential"
                    build()
                },
            )
            serverVersion = "1.8.0"
            build()
        }
        build()
    }

    val RECONNECT = with(LivekitRtc.SignalResponse.newBuilder()) {
        reconnect = with(LivekitRtc.ReconnectResponse.newBuilder()) {
            addIceServers(
                with(LivekitRtc.ICEServer.newBuilder()) {
                    addUrls("stun:stun.reconnect.com:19302")
                    username = "username"
                    credential = "credential"
                    build()
                },
            )
            clientConfiguration = with(LivekitModels.ClientConfiguration.newBuilder()) {
                forceRelay = LivekitModels.ClientConfigSetting.ENABLED
                build()
            }
            build()
        }
        build()
    }

    val OFFER = with(LivekitRtc.SignalResponse.newBuilder()) {
        offer = with(LivekitRtc.SessionDescription.newBuilder()) {
            sdp = "remote_offer"
            type = "offer"
            build()
        }
        build()
    }

    val ROOM_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
        roomUpdate = with(LivekitRtc.RoomUpdate.newBuilder()) {
            room = with(LivekitModels.Room.newBuilder()) {
                sid = "room_sid"
                metadata = "metadata"
                activeRecording = true
                build()
            }
            build()
        }
        build()
    }

    val LOCAL_TRACK_PUBLISHED = with(LivekitRtc.SignalResponse.newBuilder()) {
        trackPublished = with(LivekitRtc.TrackPublishedResponse.newBuilder()) {
            cid = "local_cid"
            track = LOCAL_AUDIO_TRACK
            build()
        }
        build()
    }

    val LOCAL_TRACK_UNPUBLISHED = with(LivekitRtc.SignalResponse.newBuilder()) {
        trackUnpublished = with(LivekitRtc.TrackUnpublishedResponse.newBuilder()) {
            trackSid = LOCAL_AUDIO_TRACK.sid
            build()
        }
        build()
    }

    val PERMISSION_CHANGE = with(LivekitRtc.SignalResponse.newBuilder()) {
        update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
            addParticipants(
                with(LOCAL_PARTICIPANT.toBuilder()) {
                    permission = with(LivekitModels.ParticipantPermission.newBuilder()) {
                        canPublish = true
                        canSubscribe = false
                        canPublishData = false
                        addCanPublishSources(LivekitModels.TrackSource.CAMERA)
                        canUpdateMetadata = false
                        canSubscribeMetrics = false
                        hidden = false
                        recorder = false
                        build()
                    }
                    build()
                },
            )
            build()
        }
        build()
    }

    val PARTICIPANT_JOIN = with(LivekitRtc.SignalResponse.newBuilder()) {
        update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
            addParticipants(REMOTE_PARTICIPANT)
            build()
        }
        build()
    }

    val PARTICIPANT_DISCONNECT = with(LivekitRtc.SignalResponse.newBuilder()) {
        update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
            val disconnectedParticipant = REMOTE_PARTICIPANT.toBuilder()
                .setState(LivekitModels.ParticipantInfo.State.DISCONNECTED)
                .build()

            addParticipants(disconnectedParticipant)
            build()
        }
        build()
    }

    val ACTIVE_SPEAKER_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
        speakersChanged = with(LivekitRtc.SpeakersChanged.newBuilder()) {
            addSpeakers(REMOTE_SPEAKER_INFO)
            build()
        }
        build()
    }

    val LOCAL_PARTICIPANT_METADATA_CHANGED = with(LivekitRtc.SignalResponse.newBuilder()) {
        update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
            val participantMetadataChanged = LOCAL_PARTICIPANT.toBuilder()
                .setMetadata("changed_metadata")
                .setName("changed_name")
                .putAttributes("attribute", "changed_value")
                .build()

            addParticipants(participantMetadataChanged)
            build()
        }
        build()
    }

    val REMOTE_PARTICIPANT_METADATA_CHANGED = with(LivekitRtc.SignalResponse.newBuilder()) {
        update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
            val participantMetadataChanged = REMOTE_PARTICIPANT.toBuilder()
                .setMetadata("changed_metadata")
                .setName("changed_name")
                .build()

            addParticipants(participantMetadataChanged)
            build()
        }
        build()
    }

    val CONNECTION_QUALITY = with(LivekitRtc.SignalResponse.newBuilder()) {
        connectionQuality = with(LivekitRtc.ConnectionQualityUpdate.newBuilder()) {
            addUpdates(
                with(LivekitRtc.ConnectionQualityInfo.newBuilder()) {
                    participantSid = JOIN.join.participant.sid
                    quality = LivekitModels.ConnectionQuality.EXCELLENT
                    build()
                },
            )
            build()
        }
        build()
    }

    val STREAM_STATE_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
        streamStateUpdate = with(LivekitRtc.StreamStateUpdate.newBuilder()) {
            addStreamStates(
                with(LivekitRtc.StreamStateInfo.newBuilder()) {
                    participantSid = REMOTE_PARTICIPANT.sid
                    trackSid = REMOTE_AUDIO_TRACK.sid
                    state = LivekitRtc.StreamState.ACTIVE
                    build()
                },
            )
            build()
        }
        build()
    }

    val SUBSCRIPTION_PERMISSION_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
        subscriptionPermissionUpdate = with(LivekitRtc.SubscriptionPermissionUpdate.newBuilder()) {
            participantSid = REMOTE_PARTICIPANT.sid
            trackSid = REMOTE_AUDIO_TRACK.sid
            allowed = false
            build()
        }
        build()
    }

    val REFRESH_TOKEN = with(LivekitRtc.SignalResponse.newBuilder()) {
        refreshToken = "refresh_token"
        build()
    }

    val PONG = with(LivekitRtc.SignalResponse.newBuilder()) {
        pong = 1L
        build()
    }

    val LEAVE = with(LivekitRtc.SignalResponse.newBuilder()) {
        leave = with(LivekitRtc.LeaveRequest.newBuilder()) {
            reason = LivekitModels.DisconnectReason.SERVER_SHUTDOWN
            build()
        }
        build()
    }

    // Data packets

    val DATA_PACKET_TRANSCRIPTION = with(LivekitModels.DataPacket.newBuilder()) {
        transcription = with(LivekitModels.Transcription.newBuilder()) {
            transcribedParticipantIdentity = JOIN.join.participant.identity // Local participant's identity
            addSegments(
                with(LivekitModels.TranscriptionSegment.newBuilder()) {
                    id = "id"
                    language = "enUS"
                    text = "This is a transcription."
                    startTime = 1
                    endTime = 10
                    final = true
                    trackId = LOCAL_AUDIO_TRACK.sid
                    build()
                },
            )
            build()
        }
        build()
    }
    val DATA_PACKET_RPC_REQUEST = with(LivekitModels.DataPacket.newBuilder()) {
        participantIdentity = REMOTE_PARTICIPANT.identity
        rpcRequest = with(LivekitModels.RpcRequest.newBuilder()) {
            id = UUID.randomUUID().toString()
            method = "hello"
            payload = "hello world"
            responseTimeoutMs = 10000
            version = 1
            build()
        }
        build()
    }
}
