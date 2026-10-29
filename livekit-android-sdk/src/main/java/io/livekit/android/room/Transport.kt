/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room

import io.livekit.android.util.Either
import livekit.LivekitRtc
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.PeerConnection.RTCConfiguration
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.SessionDescription

/**
 * The peer connections [RTCEngine] uses for one join, and the decisions that depend on how
 * they are arranged.
 *
 * Operations that only ever touch the publishing peer connection, such as adding transceivers
 * or creating client data channels, go through [publisher] directly.
 */
internal interface Transport {

    /**
     * Publishes local tracks and owns the client-created data channels.
     */
    val publisher: PeerConnectionTransport

    /**
     * Receives remote tracks and server-opened data channels.
     */
    val subscriber: PeerConnectionTransport

    /**
     * Whether the publisher connects only once something is published or sent, rather than
     * as part of joining.
     */
    val publisherConnectsOnDemand: Boolean

    /**
     * Mandatory constraints for every offer from [publisher], such as declining to receive when
     * remote media arrives on another peer connection.
     */
    val publisherOfferConstraints: List<MediaConstraints.KeyValuePair>

    /**
     * Whether the publisher has to be negotiated as part of joining, rather than on demand.
     *
     * @param hasPublished whether the publisher was in use before this join, such as before a
     * full reconnect.
     */
    fun shouldNegotiatePublisherOnJoin(hasPublished: Boolean): Boolean

    /**
     * Adds a trickled remote ICE candidate to the peer connection for [target].
     *
     * @return false if [target] does not resolve to a peer connection.
     */
    fun addIceCandidate(candidate: IceCandidate, target: LivekitRtc.SignalTarget): Boolean

    /**
     * Applies a server offer to the receiving peer connection and creates the answer to send back.
     *
     * @return the answer, which has been set as the local description, or an error message if
     * any step failed or the peer connection is closed.
     */
    suspend fun answerServerOffer(offer: SessionDescription, offerId: Int): Either<SessionDescription, String?>

    /**
     * Applies the server's answer to an offer from [publisher].
     *
     * @param midToTrackId the answer's `mid_to_track_id`, which maps the mids of media sections
     * that receive remote tracks to their track IDs. Read it back with [trackIdForReceiver].
     * @return an error message if the answer could not be applied or the peer connection is closed.
     */
    suspend fun applyServerAnswer(
        answer: SessionDescription,
        offerId: Int,
        midToTrackId: Map<String, String>,
    ): Either<Unit, String?>

    /**
     * @return the track ID the server mapped to the media section [receiver] receives on, or null
     * if there is no mapping and the track ID has to come from the track's stream instead.
     */
    fun trackIdForReceiver(receiver: RtpReceiver): String?

    /**
     * Ensures there are at least as many receive-only media sections as the server requires
     * to deliver remote tracks.
     */
    suspend fun handleMediaSectionsRequirement(requirement: LivekitRtc.MediaSectionsRequirement)

    /**
     * Prepares for a resume, which restarts ICE.
     */
    fun prepareForIceRestart()

    /**
     * Applies [config] to all peer connections.
     */
    fun updateRTCConfig(config: RTCConfiguration)

    /**
     * Suspends until the peer connections a resume depends on are connected.
     *
     * @param includePublisher whether to also wait for the publisher.
     */
    suspend fun waitUntilConnected(includePublisher: Boolean)

    /**
     * @param includePublisher whether the publisher must also be connected.
     * @return whether the peer connections a resume depends on are connected.
     */
    suspend fun isConnected(includePublisher: Boolean): Boolean

    /**
     * @return the previous answer and offer to report in a [LivekitRtc.SyncState] on resume.
     */
    suspend fun syncStateDescriptions(): SyncStateDescriptions

    /**
     * Closes all peer connections.
     */
    suspend fun close()

    /**
     * The session descriptions reported in a [LivekitRtc.SyncState].
     */
    data class SyncStateDescriptions(
        val answer: LivekitRtc.SessionDescription?,
        val offer: LivekitRtc.SessionDescription?,
    )
}
