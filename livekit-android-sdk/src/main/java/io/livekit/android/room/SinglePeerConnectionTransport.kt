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

import io.livekit.android.room.util.PeerConnectionStateObservable
import io.livekit.android.room.util.waitUntilConnected
import io.livekit.android.util.Either
import io.livekit.android.util.LKLog
import io.livekit.android.util.isLeft
import io.livekit.android.webrtc.toProtoSessionDescription
import kotlinx.coroutines.runBlocking
import livekit.LivekitRtc
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentHashMap

/**
 * A [Transport] with one peer connection that both publishes and receives. The client makes
 * every offer on [publisher], and the server opens receiving media sections on it through
 * [handleMediaSectionsRequirement].
 *
 * @param publisherState connection state of [publisher], as reported by its observer.
 * @param onTrackBound called after an answer for each receiver the server has bound to a track
 * ID that has not been attached yet. Returns whether the track was attached; a receiver that
 * was not is offered again after the next answer.
 */
@Suppress("TooManyFunctions")
internal class SinglePeerConnectionTransport(
    override val publisher: PeerConnectionTransport,
    private val publisherState: PeerConnectionStateObservable,
    private val onTrackBound: (receiver: RtpReceiver, track: MediaStreamTrack, trackId: String) -> Boolean,
) : Transport {
    private val midToTrackId: MutableMap<String, String> = ConcurrentHashMap<String, String>()

    // Receiver ID to the track ID it was last attached as.
    private val attachedTrackIds: MutableMap<String, String> = ConcurrentHashMap<String, String>()
    override val subscriber: PeerConnectionTransport
        get() = publisher
    override val publisherConnectsOnDemand: Boolean
        get() = false
    override val publisherOfferConstraints: List<MediaConstraints.KeyValuePair>
        get() = emptyList()

    override fun shouldNegotiatePublisherOnJoin(hasPublished: Boolean): Boolean {
        return true
    }

    override fun addIceCandidate(candidate: IceCandidate, target: LivekitRtc.SignalTarget): Boolean {
        if (target == LivekitRtc.SignalTarget.SUBSCRIBER || target == LivekitRtc.SignalTarget.PUBLISHER) {
            publisher.addIceCandidate(candidate)
            return true
        }
        LKLog.i { "unknown ice candidate target: $target" }
        return false
    }

    override suspend fun answerServerOffer(
        offer: SessionDescription,
        offerId: Int,
    ): Either<SessionDescription, String?> {
        return Either.Right("Received offer but not in dual PC mode, ignoring")
    }

    override suspend fun applyServerAnswer(
        answer: SessionDescription,
        offerId: Int,
        midToTrackId: Map<String, String>,
    ): Either<Unit, String?> {
        if (publisher.isStaleAnswer(offerId)) {
            return Either.Right("Old offer, ignoring: $offerId")
        }
        // libwebrtc reports new tracks while it applies the description, and those lookups
        // read this mapping.
        this.midToTrackId.putAll(midToTrackId)

        val result = publisher.setRemoteDescription(answer, offerId)
        if (result.isLeft()) {
            attachBoundTracks()
        }
        return result
    }

    /**
     * Offers [onTrackBound] every receiver whose media section is mapped to a track ID it is
     * not attached as. libwebrtc reports a track only when its section starts receiving, not
     * when the server binds another track to a section that already is.
     */
    private suspend fun attachBoundTracks() {
        publisher.withPeerConnection {
            for (transceiver in transceivers) {
                val trackId = transceiver.mid?.let { midToTrackId[it] } ?: continue
                val receiver = transceiver.receiver ?: continue
                val track = receiver.track() ?: continue
                if (attachedTrackIds[receiver.id()] == trackId) {
                    continue
                }
                if (onTrackBound(receiver, track, trackId)) {
                    attachedTrackIds[receiver.id()] = trackId
                }
            }
        }
    }

    override fun trackIdForReceiver(receiver: RtpReceiver): String? {
        val receiverId = receiver.id()
        val mid = runBlocking {
            publisher.withPeerConnection {
                transceivers.firstOrNull { it.receiver.id() == receiverId }?.mid
            }
        } ?: return null
        return midToTrackId[mid]
    }

    override suspend fun handleMediaSectionsRequirement(requirement: LivekitRtc.MediaSectionsRequirement) {
        if (publisher.isClosed()) {
            return
        }
        val transceiverInit = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        publisher.withPeerConnection {
            for (i in 0..<requirement.numAudios) {
                addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, transceiverInit)
            }
            for (i in 0..<requirement.numVideos) {
                addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, transceiverInit)
            }
        }
    }

    override fun prepareForIceRestart() {
        // No-op for single PC.
    }

    override fun updateRTCConfig(config: PeerConnection.RTCConfiguration) {
        publisher.updateRTCConfig(config)
    }

    override suspend fun waitUntilConnected(includePublisher: Boolean) {
        publisherState.waitUntilConnected()
    }

    override suspend fun isConnected(includePublisher: Boolean): Boolean {
        return publisher.isConnected()
    }

    override suspend fun syncStateDescriptions(): Transport.SyncStateDescriptions {
        var answer: LivekitRtc.SessionDescription? = null
        var offer: LivekitRtc.SessionDescription? = null
        publisher.withPeerConnection {
            answer = remoteDescription?.toProtoSessionDescription()
            offer = localDescription?.toProtoSessionDescription()
        }
        return Transport.SyncStateDescriptions(answer, offer)
    }

    override suspend fun close() {
        publisher.close()
    }
}
