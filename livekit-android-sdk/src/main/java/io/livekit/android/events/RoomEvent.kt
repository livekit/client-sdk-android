package io.livekit.android.events

import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication

sealed class RoomEvent : Event() {
    /**
     * A network change has been detected and LiveKit attempts to reconnect to the room
     * When reconnect attempts succeed, the room state will be kept, including tracks that are subscribed/published
     */
    class Reconnecting(val room: Room): RoomEvent()

    /**
     * The reconnect attempt had been successful
     */
    class Reconnected(val room: Room): RoomEvent()

    /**
     * Disconnected from room
     */
    class Disconnected(val room: Room, val error: Exception?): RoomEvent()

    /**
     * When a [RemoteParticipant] joins after the local participant. It will not emit events
     * for participants that are already in the room
     */
    class ParticipantConnected(val room: Room, val participant: RemoteParticipant): RoomEvent()

    /**
     * When a [RemoteParticipant] leaves after the local participant has joined.
     */
    class ParticipantDisconnected(val room: Room, val participant: RemoteParticipant): RoomEvent()

    /**
     * Active speakers changed. List of speakers are ordered by their audio level. loudest
     * speakers first. This will include the [LocalParticipant] too.
     */
    class ActiveSpeakersChanged(val room: Room, val speakers: List<Participant>,): RoomEvent()

    // Participant callbacks
    /**
     * Participant metadata is a simple way for app-specific state to be pushed to all users.
     * When RoomService.UpdateParticipantMetadata is called to change a participant's state,
     * this event will be fired for all clients in the room.
     */
    class MetadataChanged(val room: Room, val participant: Participant, val prevMetadata: String?): RoomEvent()

    /**
     * The participant was muted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    class TrackMuted(val room: Room, val publication: TrackPublication, val participant: Participant): RoomEvent()

    /**
     * The participant was unmuted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    class TrackUnmuted(val room: Room, val publication: TrackPublication, val participant: Participant): RoomEvent()

    /**
     * When a new track is published to room after the local participant has joined. It will
     * not fire for tracks that are already published
     */
    class TrackPublished(val room: Room, val publication: TrackPublication, val participant: Participant): RoomEvent()

    /**
     * A [Participant] has unpublished a track
     */
    class TrackUnpublished(val room: Room, val publication: TrackPublication, val participant: Participant): RoomEvent()

    /**
     * The [LocalParticipant] has subscribed to a new track. This event will always fire as
     * long as new tracks are ready for use.
     */
    class TrackSubscribed(val room: Room, val track: Track, val publication: TrackPublication, val participant: RemoteParticipant): RoomEvent()

    /**
     * Could not subscribe to a track
     */
    class TrackSubscriptionFailed(val room: Room, val sid: String, val exception: Exception, val participant: RemoteParticipant): RoomEvent()

    /**
     * A subscribed track is no longer available. Clients should listen to this event and ensure
     * the track removes all renderers
     */
    class TrackUnsubscribed(val room: Room, val track: Track, val publications: TrackPublication, val participant: RemoteParticipant): RoomEvent()

    /**
     * Received data published by another participant
     */
    class DataReceived(val room: Room, val data: ByteArray, val participant: RemoteParticipant): RoomEvent()

    /**
     * The connection quality for a participant has changed.
     *
     * @param participant Either a remote participant or [Room.localParticipant]
     * @param quality the new connection quality
     */
    class ConnectionQualityChanged(val room: Room, val participant: Participant, val quality: ConnectionQuality): RoomEvent()

}