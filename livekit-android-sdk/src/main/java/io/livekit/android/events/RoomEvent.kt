package io.livekit.android.events

import io.livekit.android.room.Room
import io.livekit.android.room.participant.*
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication

sealed class RoomEvent(val room: Room) : Event() {
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
    class Disconnected(room: Room, val error: Exception?) : RoomEvent(room)

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
        val prevMetadata: String?
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
        val prevMetadata: String?
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
        val participant: RemoteParticipant
    ) : RoomEvent(room)

    /**
     * Could not subscribe to a track
     */
    class TrackSubscriptionFailed(
        room: Room,
        val sid: String,
        val exception: Exception,
        val participant: RemoteParticipant
    ) : RoomEvent(room)

    /**
     * A subscribed track is no longer available. Clients should listen to this event and ensure
     * the track removes all renderers
     */
    class TrackUnsubscribed(
        room: Room,
        val track: Track,
        val publications: TrackPublication,
        val participant: RemoteParticipant
    ) : RoomEvent(room)

    /**
     * A track's stream state has changed.
     */
    class TrackStreamStateChanged(
        room: Room,
        val trackPublication: TrackPublication,
        val streamState: Track.StreamState
    ) : RoomEvent(room)

    /**
     * A remote track's subscription permissions have changed.
     */
    class TrackSubscriptionPermissionChanged(
        room: Room,
        val participant: RemoteParticipant,
        val trackPublication: RemoteTrackPublication,
        val subscriptionAllowed: Boolean
    ) : RoomEvent(room)

    /**
     * Received data published by another participant
     */
    class DataReceived(room: Room, val data: ByteArray, val participant: RemoteParticipant) : RoomEvent(room)

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
}