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

import io.livekit.android.room.util.MediaConstraintKeys
import io.livekit.android.room.util.PeerConnectionStateObservable
import io.livekit.android.room.util.createAnswer
import io.livekit.android.room.util.setLocalDescription
import io.livekit.android.room.util.waitUntilConnected
import io.livekit.android.util.Either
import io.livekit.android.util.LKLog
import io.livekit.android.util.isRight
import io.livekit.android.util.nullSafe
import io.livekit.android.webrtc.toProtoSessionDescription
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import livekit.LivekitRtc
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.PeerConnection.RTCConfiguration
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.SessionDescription

/**
 * A [Transport] with separate publishing and receiving peer connections. The server offers
 * on [subscriber] and the client offers on [publisher].
 *
 * @param publisherState connection state of [publisher], as reported by its observer.
 * @param subscriberState connection state of [subscriber], as reported by its observer.
 * @param subscriberPrimary the server's `subscriber_primary` flag from the join response.
 * @param fastPublish the server's `fast_publish` flag from the join response.
 */
internal class DualPeerConnectionTransport(
    override val publisher: PeerConnectionTransport,
    override val subscriber: PeerConnectionTransport,
    private val publisherState: PeerConnectionStateObservable,
    private val subscriberState: PeerConnectionStateObservable,
    private val subscriberPrimary: Boolean,
    private val fastPublish: Boolean,
) : Transport {
    override val publisherConnectsOnDemand: Boolean
        get() = subscriberPrimary
    override val publisherOfferConstraints: List<MediaConstraints.KeyValuePair> = listOf(
        MediaConstraints.KeyValuePair(MediaConstraintKeys.OFFER_TO_RECV_AUDIO, MediaConstraintKeys.FALSE),
        MediaConstraints.KeyValuePair(MediaConstraintKeys.OFFER_TO_RECV_VIDEO, MediaConstraintKeys.FALSE),
    )

    override fun shouldNegotiatePublisherOnJoin(hasPublished: Boolean): Boolean {
        return !subscriberPrimary || hasPublished || fastPublish
    }

    override fun addIceCandidate(candidate: IceCandidate, target: LivekitRtc.SignalTarget): Boolean {
        when (target) {
            LivekitRtc.SignalTarget.PUBLISHER -> {
                publisher.addIceCandidate(candidate)
                return true
            }

            LivekitRtc.SignalTarget.SUBSCRIBER -> {
                subscriber.addIceCandidate(candidate)
                return true
            }
            else -> {
                LKLog.i { "unknown ice candidate target: $target" }
                return false
            }
        }
    }

    override suspend fun answerServerOffer(offer: SessionDescription, offerId: Int): Either<SessionDescription, String?> {
        val remote = subscriber.setRemoteDescription(offer, offerId)
        if (remote.isRight()) { return remote }

        val answer = subscriber.withPeerConnection { createAnswer(MediaConstraints()) }.nullSafe()
        if (answer.isRight()) { return answer }

        val outcome = subscriber.withPeerConnection { setLocalDescription(answer.value) }.nullSafe()
        if (outcome.isRight()) {
            // This is a failure
            return outcome
        } else {
            // The caller wants the answer on success
            return answer
        }
    }

    override suspend fun applyServerAnswer(
        answer: SessionDescription,
        offerId: Int,
        midToTrackId: Map<String, String>,
    ): Either<Unit, String?> {
        return publisher.setRemoteDescription(answer, offerId)
    }

    override fun trackIdForReceiver(receiver: RtpReceiver): String? {
        return null
    }

    override suspend fun handleMediaSectionsRequirement(requirement: LivekitRtc.MediaSectionsRequirement) {
        // Not needed for dual transport.
    }

    override fun prepareForIceRestart() {
        subscriber.prepareForIceRestart()
    }

    override fun updateRTCConfig(config: RTCConfiguration) {
        subscriber.updateRTCConfig(config)
        publisher.updateRTCConfig(config)
    }

    override suspend fun waitUntilConnected(includePublisher: Boolean) = coroutineScope {
        if (includePublisher) {
            launch { publisherState.waitUntilConnected() }
        }
        launch { subscriberState.waitUntilConnected() }
        Unit
    }

    override suspend fun isConnected(includePublisher: Boolean): Boolean {
        if (includePublisher) {
            return publisher.isConnected() && subscriber.isConnected()
        }
        return subscriber.isConnected()
    }

    override suspend fun syncStateDescriptions(): Transport.SyncStateDescriptions {
        var answer: LivekitRtc.SessionDescription? = null
        var offer: LivekitRtc.SessionDescription? = null
        subscriber.withPeerConnection {
            answer = localDescription?.toProtoSessionDescription()
            offer = remoteDescription?.toProtoSessionDescription()
        }
        return Transport.SyncStateDescriptions(answer, offer)
    }

    override suspend fun close() {
        publisher.close()
        subscriber.close()
    }
}
