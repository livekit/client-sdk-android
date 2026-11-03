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
 */
@Suppress("TooManyFunctions")
internal class SinglePeerConnectionTransport(
    override val publisher: PeerConnectionTransport,
    private val publisherState: PeerConnectionStateObservable,
) : Transport {
    private val midToTrackId: MutableMap<String, String> = ConcurrentHashMap<String, String>()
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
        for (k in midToTrackId) {
            this.midToTrackId[k.key] = k.value
        }
        val res = publisher.setRemoteDescription(answer, offerId)
        return res
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

        // Adding transceiver triggers the onRenegotiationNeeded event and triggers negotiation.
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
