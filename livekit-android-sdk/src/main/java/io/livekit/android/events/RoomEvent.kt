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

package io.livekit.android.events

import io.livekit.android.annotations.Beta
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.ParticipantPermission
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.types.TranscriptionSegment
import livekit.LivekitModels

sealed class RoomEvent(val room: Room) : Event() {

    /**
     * Connected to Room
     */
    class Connected(room: Room) : RoomEvent(room)

    /**
     * A network change has been detected and LiveKit attempts to reconnect to the room
     * When reconnect attempts succeed, the room state will be kept, including tracks that are subscribed/published
     */
    class Reconnecting(room: Room) : RoomEvent(room)

    /**
     * The reconnect attempt had been successful
     */
    class Reconnected(room: Room) : RoomEvent(room)

    /**
     * Disconnected from room
     */
    class Disconnected(room: Room, val error: Exception?, val reason: DisconnectReason) : RoomEvent(room)

    /**
     * When a [RemoteParticipant] joins after the local participant. It will not emit events
     * for participants that are already in the room
     */
    class ParticipantConnected(room: Room, val participant: RemoteParticipant) : RoomEvent(room)

    /**
     * When a [RemoteParticipant] leaves after the local participant has joined.
     */
    class ParticipantDisconnected(room: Room, val participant: RemoteParticipant) : RoomEvent(room)

    /**
     * Active speakers changed. List of speakers are ordered by their audio level. loudest
     * speakers first. This will include the [LocalParticipant] too.
     */
    class ActiveSpeakersChanged(room: Room, val speakers: List<Participant>) : RoomEvent(room)

    class RoomMetadataChanged(
        room: Room,
        val newMetadata: String?,
        val prevMetadata: String?,
    ) : RoomEvent(room)

    // Participant callbacks
    /**
     * Participant metadata is a simple way for app-specific state to be pushed to all users.
     * When RoomService.UpdateParticipantMetadata is called to change a participant's state,
     * this event will be fired for all clients in the room.
     */
    class ParticipantMetadataChanged(
        room: Room,
        val participant: Participant,
        val prevMetadata: String?,
    ) : RoomEvent(room)

    /**
     * When a participant's attributes are changed, fired for all participants
     */
    class ParticipantAttributesChanged(
        room: Room,
        val participant: Participant,
        /**
         * The attributes that have changed and their new associated values.
         */
        val changedAttributes: Map<String, String>,
        /**
         * The old attributes prior to change.
         */
        val oldAttributes: Map<String, String>,
    ) : RoomEvent(room)

    class ParticipantNameChanged(
        room: Room,
        val participant: Participant,
        val name: String?,
    ) : RoomEvent(room)

    /**
     * The participant was muted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    class TrackMuted(room: Room, val publication: TrackPublication, val participant: Participant) : RoomEvent(room)

    /**
     * The participant was unmuted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    class TrackUnmuted(room: Room, val publication: TrackPublication, val participant: Participant) : RoomEvent(room)

    /**
     * Fired when the first remote participant has subscribed to the localParticipant's track
     */
    class LocalTrackSubscribed(room: Room, val publication: LocalTrackPublication, val participant: LocalParticipant) : RoomEvent(room)

    /**
     * When a new track is published to room after the local participant has joined. It will
     * not fire for tracks that are already published
     */
    class TrackPublished(room: Room, val publication: TrackPublication, val participant: Participant) : RoomEvent(room)

    /**
     * A [Participant] has unpublished a track
     */
    class TrackUnpublished(room: Room, val publication: TrackPublication, val participant: Participant) :
        RoomEvent(room)

    /**
     * The [LocalParticipant] has subscribed to a new track. This event will always fire as
     * long as new tracks are ready for use.
     */
    class TrackSubscribed(
        room: Room,
        val track: Track,
        val publication: TrackPublication,
        val participant: RemoteParticipant,
    ) : RoomEvent(room)

    /**
     * Could not subscribe to a track
     */
    class TrackSubscriptionFailed(
        room: Room,
        val sid: String,
        val exception: Exception,
        val participant: RemoteParticipant,
    ) : RoomEvent(room)

    /**
     * A subscribed track is no longer available. Clients should listen to this event and ensure
     * the track removes all renderers
     */
    class TrackUnsubscribed(
        room: Room,
        val track: Track,
        val publications: TrackPublication,
        val participant: RemoteParticipant,
    ) : RoomEvent(room)

    /**
     * A track's stream state has changed.
     */
    class TrackStreamStateChanged(
        room: Room,
        val trackPublication: TrackPublication,
        val streamState: Track.StreamState,
    ) : RoomEvent(room)

    /**
     * A remote track's subscription permissions have changed.
     */
    class TrackSubscriptionPermissionChanged(
        room: Room,
        val participant: RemoteParticipant,
        val trackPublication: RemoteTrackPublication,
        val subscriptionAllowed: Boolean,
    ) : RoomEvent(room)

    /**
     * Received data published by another participant
     *
     * @param data the published data
     * @param participant the participant if available
     */
    class DataReceived(room: Room, val data: ByteArray, val participant: RemoteParticipant?, val topic: String?) :
        RoomEvent(room)

    /**
     * The connection quality for a participant has changed.
     *
     * @param participant Either a remote participant or [Room.localParticipant]
     * @param quality the new connection quality
     */
    class ConnectionQualityChanged(room: Room, val participant: Participant, val quality: ConnectionQuality) :
        RoomEvent(room)

    class FailedToConnect(room: Room, val error: Throwable) : RoomEvent(room)

    /**
     * A participant's permissions have changed.
     *
     * Currently only fires for the local participant.
     */
    class ParticipantPermissionsChanged(
        room: Room,
        val participant: Participant,
        val newPermissions: ParticipantPermission?,
        val oldPermissions: ParticipantPermission?,
    ) : RoomEvent(room)

    /**
     * The recording of a room has started/stopped.
     */
    class RecordingStatusChanged(room: Room, isRecording: Boolean) : RoomEvent(room)

    /**
     * The E2EE state of a track has changed.
     */
    class TrackE2EEStateEvent(
        room: Room,
        val track: Track,
        val publication: TrackPublication,
        val participant: Participant,
        var state: E2EEState,
    ) : RoomEvent(room)

    @Beta
    class TranscriptionReceived(
        room: Room,
        /**
         * The transcription segments.
         */
        val transcriptionSegments: List<TranscriptionSegment>,
        /**
         * The applicable participant these transcriptions apply to.
         */
        val participant: Participant?,
        /**
         * The applicable track publication these transcriptions apply to.
         */
        val publication: TrackPublication?,
    ) : RoomEvent(room)
}

enum class DisconnectReason {
    UNKNOWN_REASON,
    CLIENT_INITIATED,
    DUPLICATE_IDENTITY,
    SERVER_SHUTDOWN,
    PARTICIPANT_REMOVED,
    ROOM_DELETED,
    STATE_MISMATCH,
    JOIN_FAILURE,
    MIGRATION,
    SIGNAL_CLOSE,
    ROOM_CLOSED,
    USER_UNAVAILABLE,
    USER_REJECTED,
    SIP_TRUNK_FAILURE,
}

/**
 * @suppress
 */
fun LivekitModels.DisconnectReason?.convert(): DisconnectReason {
    return when (this) {
        LivekitModels.DisconnectReason.CLIENT_INITIATED -> DisconnectReason.CLIENT_INITIATED
        LivekitModels.DisconnectReason.DUPLICATE_IDENTITY -> DisconnectReason.DUPLICATE_IDENTITY
        LivekitModels.DisconnectReason.SERVER_SHUTDOWN -> DisconnectReason.SERVER_SHUTDOWN
        LivekitModels.DisconnectReason.PARTICIPANT_REMOVED -> DisconnectReason.PARTICIPANT_REMOVED
        LivekitModels.DisconnectReason.ROOM_DELETED -> DisconnectReason.ROOM_DELETED
        LivekitModels.DisconnectReason.STATE_MISMATCH -> DisconnectReason.STATE_MISMATCH
        LivekitModels.DisconnectReason.JOIN_FAILURE -> DisconnectReason.JOIN_FAILURE
        LivekitModels.DisconnectReason.MIGRATION -> DisconnectReason.MIGRATION
        LivekitModels.DisconnectReason.SIGNAL_CLOSE -> DisconnectReason.SIGNAL_CLOSE
        LivekitModels.DisconnectReason.ROOM_CLOSED -> DisconnectReason.ROOM_CLOSED
        LivekitModels.DisconnectReason.USER_UNAVAILABLE -> DisconnectReason.USER_UNAVAILABLE
        LivekitModels.DisconnectReason.USER_REJECTED -> DisconnectReason.USER_REJECTED
        LivekitModels.DisconnectReason.SIP_TRUNK_FAILURE -> DisconnectReason.SIP_TRUNK_FAILURE
        LivekitModels.DisconnectReason.UNKNOWN_REASON,
        LivekitModels.DisconnectReason.UNRECOGNIZED,
        null,
        -> DisconnectReason.UNKNOWN_REASON
    }
}
