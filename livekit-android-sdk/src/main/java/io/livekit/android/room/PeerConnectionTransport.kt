/*
 * Copyright 2023-2026 LiveKit, Inc.
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

import android.javax.sdp.MediaDescription
import android.javax.sdp.SdpFactory
import androidx.annotation.VisibleForTesting
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.util.MediaConstraintKeys
import io.livekit.android.room.util.createOffer
import io.livekit.android.room.util.findConstraint
import io.livekit.android.room.util.setLocalDescription
import io.livekit.android.room.util.setRemoteDescription
import io.livekit.android.util.Either
import io.livekit.android.util.LKLog
import io.livekit.android.util.debounce
import io.livekit.android.webrtc.SdpExt
import io.livekit.android.webrtc.SdpFmtp
import io.livekit.android.webrtc.getExts
import io.livekit.android.webrtc.getFmtps
import io.livekit.android.webrtc.getMsid
import io.livekit.android.webrtc.getRtps
import io.livekit.android.webrtc.isConnected
import io.livekit.android.webrtc.peerconnection.RTCThreadToken
import io.livekit.android.webrtc.peerconnection.executeBlockingOnRTCThread
import io.livekit.android.webrtc.peerconnection.launchBlockingOnRTCThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnection.RTCConfiguration
import livekit.org.webrtc.PeerConnection.SignalingState
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SessionDescription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Named
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.roundToLong

/**
 * @suppress
 */
internal class PeerConnectionTransport
@AssistedInject
constructor(
    @Assisted config: RTCConfiguration,
    @Assisted pcObserver: PeerConnection.Observer,
    @Assisted private val listener: Listener?,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    connectionFactory: PeerConnectionFactory,
    private val sdpFactory: SdpFactory,
    private val rtcThreadToken: RTCThreadToken,
) {
    private val coroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())

    @VisibleForTesting
    internal val peerConnection: PeerConnection = executeBlockingOnRTCThread(rtcThreadToken) {
        connectionFactory.createPeerConnection(
            config,
            pcObserver,
        ) ?: throw IllegalStateException("peer connection creation failed?")
    }!!
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var restartingIce: Boolean = false

    private var renegotiate = false

    private val trackBitrates = mutableMapOf<TrackBitrateInfoKey, TrackBitrateInfo>()
    // x-google-start-bitrate is a connection-level BWE hint in libwebrtc. Keep it
    // available through data-channel/audio-only offers and consume it only after a
    // local video m-section successfully gets the hint.
    private var hasAppliedVideoStartBitrate = false
    private var isClosed = AtomicBoolean(false)

    private val latestOfferId = AtomicInteger(0)

    interface Listener {
        fun onOffer(sd: SessionDescription, offerId: Int)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        executeRTCIfNotClosed {
            if (peerConnection.remoteDescription != null && !restartingIce) {
                peerConnection.addIceCandidate(candidate)
            } else {
                pendingCandidates.add(candidate)
            }
        }
    }

    suspend fun <T> withPeerConnection(action: suspend PeerConnection.() -> T): T? {
        return launchRTCIfNotClosed {
            action(peerConnection)
        }
    }

    suspend fun setRemoteDescription(sd: SessionDescription, offerId: Int): Either<Unit, String?> {
        val result = launchRTCIfNotClosed {
            val currentOfferId = latestOfferId.get()
            if (sd.type == SessionDescription.Type.ANSWER && currentOfferId > 0 && offerId > 0 && currentOfferId > offerId) {
                return@launchRTCIfNotClosed Either.Right("Old offer, ignoring. Expected: $currentOfferId, actual: $offerId")
            }
            val result = peerConnection.setRemoteDescription(sd)
            if (result is Either.Left) {
                pendingCandidates.forEach { pending ->
                    peerConnection.addIceCandidate(pending)
                }
                pendingCandidates.clear()
                restartingIce = false
            }
            return@launchRTCIfNotClosed result
        } ?: Either.Right("PCT is closed.")

        if (this.renegotiate) {
            this.renegotiate = false
            this.createAndSendOffer()
        }

        return result
    }

    val negotiate = debounce<MediaConstraints?, Unit>(20, coroutineScope) {
        if (it != null) {
            createAndSendOffer(it)
        } else {
            createAndSendOffer()
        }
    }

    private val offerLock = Mutex()
    private suspend fun createAndSendOffer(constraints: MediaConstraints = MediaConstraints()) {
        offerLock.withLock {
            if (listener == null) {
                return
            }

            var offerId = -1
            var finalSdp: SessionDescription? = null

            // TODO: This is a potentially long lock hold. May need to break up.
            launchRTCIfNotClosed {
                val iceRestart =
                    constraints.findConstraint(MediaConstraintKeys.ICE_RESTART) == MediaConstraintKeys.TRUE
                if (iceRestart) {
                    LKLog.d { "restarting ice" }
                    restartingIce = true
                }

                if (peerConnection.signalingState() == SignalingState.HAVE_LOCAL_OFFER) {
                    // we're waiting for the peer to accept our offer, so we'll just wait
                    // the only exception to this is when ICE restart is needed
                    val curSd = peerConnection.remoteDescription
                    if (iceRestart && curSd != null) {
                        // TODO: handle when ICE restart is needed but we don't have a remote description
                        // the best thing to do is to recreate the peerconnection
                        peerConnection.setRemoteDescription(curSd)
                    } else {
                        renegotiate = true
                        return@launchRTCIfNotClosed
                    }
                }

                // actually negotiate

                // increase the offer id at the start to ensure the offer is always > 0
                // so that we can use 0 as a default value for legacy behavior
                // this may skip some ids, but is not an issue.
                offerId = latestOfferId.incrementAndGet()

                val sdpOffer = when (val outcome = peerConnection.createOffer(constraints)) {
                    is Either.Left -> outcome.value
                    is Either.Right -> {
                        LKLog.d { "error creating offer: ${outcome.value}" }
                        return@launchRTCIfNotClosed
                    }
                }

                if (isClosed()) {
                    return@launchRTCIfNotClosed
                }
                // munge sdp
                val sdpDescription = sdpFactory.createSessionDescription(sdpOffer.description)

                val mediaDescs = sdpDescription.getMediaDescriptions(true)
                    .filterIsInstance<MediaDescription>()
                // The publisher PeerConnection may negotiate before any video is published
                // (for example, data channel only or audio first). Those offers should not
                // consume the video start hint. When the first video offer is created, use
                // one connection-level value across all video m-sections so libwebrtc's
                // last-writer-wins handling cannot depend on SDP m-section order.
                val connectionStartBitrate = if (!hasAppliedVideoStartBitrate) {
                    computeConnectionStartBitrate(mediaDescs, trackBitrates)
                } else {
                    null
                }
                var appliedVideoStartBitrate = false
                for (mediaDesc in mediaDescs) {
                    if (mediaDesc.media.mediaType == "audio") {
                        // TODO
                    } else if (mediaDesc.media.mediaType == "video") {
                        ensureVideoDDExtensionForSVC(mediaDesc)
                        appliedVideoStartBitrate = ensureCodecBitrates(
                            mediaDesc,
                            trackBitrates = trackBitrates,
                            connectionStartBitrate = connectionStartBitrate,
                        ) || appliedVideoStartBitrate
                    }
                }
                val mungedDescription = sdpDescription.toString()
                finalSdp = setMungedSdp(sdpOffer, mungedDescription)
                // setMungedSdp may fall back to the original SDP. Only mark the one-shot
                // hint as used after the SDP with the hint is accepted locally.
                if (appliedVideoStartBitrate && finalSdp?.description == mungedDescription) {
                    hasAppliedVideoStartBitrate = true
                }
            }

            finalSdp?.let { sdp ->
                val currentOfferId = latestOfferId.get()
                if (offerId < 0) {
                    LKLog.w { "createAndSendOffer: invalid offer id?" }
                    return
                }
                if (currentOfferId > offerId) {
                    LKLog.i { "createAndSendOffer: simultaneous offer attempt? current: $currentOfferId, offer attempt: $offerId" }
                    return
                }
                listener.onOffer(sdp, offerId)
            }
        }
    }

    private suspend fun setMungedSdp(sdp: SessionDescription, mungedDescription: String, remote: Boolean = false): SessionDescription {
        val mungedSdp = SessionDescription(sdp.type, mungedDescription)

        LKLog.v { "sdp type: ${sdp.type}\ndescription:\n${sdp.description}" }
        LKLog.v { "munged sdp type: ${mungedSdp.type}\ndescription:\n${mungedSdp.description}" }

        val mungedResult = launchRTCIfNotClosed {
            if (remote) {
                peerConnection.setRemoteDescription(mungedSdp)
            } else {
                peerConnection.setLocalDescription(mungedSdp)
            }
        } ?: Either.Right("PCT closed")

        val mungedErrorMessage = when (mungedResult) {
            is Either.Left -> {
                // munged sdp set successfully.
                return mungedSdp
            }

            is Either.Right -> {
                if (mungedResult.value.isNullOrBlank()) {
                    "unknown sdp error"
                } else {
                    mungedResult.value
                }
            }
        }

        // munged sdp setting failed
        LKLog.w {
            "setting munged sdp for " +
                "${if (remote) "remote" else "local"} description, " +
                "${mungedSdp.type} type failed, falling back to unmodified."
        }
        LKLog.w { "error: $mungedErrorMessage" }

        val result = launchRTCIfNotClosed {
            if (remote) {
                peerConnection.setRemoteDescription(sdp)
            } else {
                peerConnection.setLocalDescription(sdp)
            }
        } ?: Either.Right("PCT closed")

        if (result is Either.Right) {
            val errorMessage = if (result.value.isNullOrBlank()) {
                "unknown sdp error"
            } else {
                result.value
            }

            // sdp setting failed
            LKLog.w {
                "setting original sdp for " +
                    "${if (remote) "remote" else "local"} description, " +
                    "${sdp.type} type failed!"
            }
            LKLog.w { "error: $errorMessage" }
        }
        return sdp
    }

    fun prepareForIceRestart() {
        restartingIce = true
    }

    fun isClosed() = isClosed.get()

    fun closeBlocking() {
        runBlocking {
            close()
        }
    }

    suspend fun close() {
        launchRTCIfNotClosed {
            isClosed.set(true)
            peerConnection.dispose()
        }
        coroutineScope.cancel()
    }

    fun updateRTCConfig(config: RTCConfiguration) {
        executeRTCIfNotClosed {
            peerConnection.setConfiguration(config)
        }
    }

    fun registerTrackBitrateInfo(cid: String, trackBitrateInfo: TrackBitrateInfo) {
        trackBitrates[TrackBitrateInfoKey.Cid(cid)] = trackBitrateInfo
    }

    fun registerTrackBitrateInfo(transceiver: RtpTransceiver, trackBitrateInfo: TrackBitrateInfo) {
        trackBitrates[TrackBitrateInfoKey.Transceiver(transceiver)] = trackBitrateInfo
    }

    suspend fun isConnected(): Boolean {
        return launchRTCIfNotClosed {
            peerConnection.isConnected()
        } ?: false
    }

    suspend fun iceConnectionState(): PeerConnection.IceConnectionState {
        return launchRTCIfNotClosed {
            peerConnection.iceConnectionState()
        } ?: PeerConnection.IceConnectionState.CLOSED
    }

    suspend fun connectionState(): PeerConnection.PeerConnectionState {
        return launchRTCIfNotClosed {
            peerConnection.connectionState()
        } ?: PeerConnection.PeerConnectionState.CLOSED
    }

    suspend fun signalingState(): SignalingState {
        return launchRTCIfNotClosed {
            peerConnection.signalingState()
        } ?: SignalingState.CLOSED
    }

    @OptIn(ExperimentalContracts::class)
    private suspend inline fun <T> launchRTCIfNotClosed(noinline action: suspend CoroutineScope.() -> T): T? {
        contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
        if (isClosed()) {
            return null
        }
        return launchBlockingOnRTCThread(rtcThreadToken) {
            return@launchBlockingOnRTCThread if (isClosed()) {
                null
            } else {
                action()
            }
        }
    }

    @OptIn(ExperimentalContracts::class)
    private fun <T> executeRTCIfNotClosed(action: () -> T): T? {
        contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
        if (isClosed()) {
            return null
        }
        return executeBlockingOnRTCThread(rtcThreadToken) {
            return@executeBlockingOnRTCThread if (isClosed()) {
                null
            } else {
                action()
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            config: RTCConfiguration,
            pcObserver: PeerConnection.Observer,
            listener: Listener?,
        ): PeerConnectionTransport
    }
}

private const val DD_EXTENSION_URI = "https://aomediacodec.github.io/av1-rtp-spec/#dependency-descriptor-rtp-header-extension"

/**
 * @suppress
 */
@VisibleForTesting
fun ensureVideoDDExtensionForSVC(mediaDesc: MediaDescription) {
    val codec = mediaDesc.getRtps()
        .firstOrNull()
        ?.second
        ?.codec ?: return
    if (!isSVCCodec(codec)) {
        return
    }

    var maxId = 0L

    val ddFound = mediaDesc.getExts().any { (_, ext) ->
        if (ext.uri == DD_EXTENSION_URI) {
            return@any true
        }
        if (ext.value > maxId) {
            maxId = ext.value
        }
        false
    }

    // Not found, add manually
    if (!ddFound) {
        mediaDesc.addAttribute(
            SdpExt(
                value = maxId + 1,
                uri = DD_EXTENSION_URI,
                config = null,
                direction = null,
                encryptUri = null,
            ).toAttributeField(),
        )
    }
}

/*
 * Video codecs use a very low bitrate at the beginning and increase slowly by
 * the bandwidth estimator until they reach the target bitrate. The process commonly
 * costs more than 10 seconds causing subscribers to get blurry video at the first
 * few seconds. We use x-google-start-bitrate to hint the BWE to start higher.
 *
 * Why 90%: Gives ~10% headroom for bandwidth estimation while starting close to target.
 * Why same for all codecs: Target bitrate already accounts for codec efficiency
 * (e.g., users set lower targets for VP9/AV1 knowing they're more efficient).
 * Why cap camera at 1 Mbps: Prevents BWE from starting too aggressively on high bitrate tracks.
 *
 * libwebrtc applies these codec fmtp bitrate params to the shared Call, not just
 * the m-section that carries them. To avoid last-writer-wins variance, each video
 * m-section gets the same x-google-start-bitrate: the max hint among active video
 * m-sections in the first offer that contains local video. Later renegotiations do
 * not write it, because reapplying a start hint can reset an already-running
 * bandwidth estimator.
 *
 * Do not write x-google-max-bitrate here. libwebrtc promotes this SDP fmtp
 * value into the shared Call max_data_rate, so one video m-section can cap the
 * whole publisher connection and throttle unrelated concurrent tracks, such as
 * camera plus screen share. The track-specific limit belongs in
 * RtpParameters.Encoding.maxBitrateBps, where per-track and per-layer caps are
 * already applied. Keep this behavior aligned across LiveKit SDKs by relying on
 * encoding parameters for max bitrate and reserving SDP munging for the one
 * connection-level start bitrate hint.
 */
private const val startBitrateMultiplier = 0.9

/** Maximum x-google-start-bitrate in kbps. 1 Mbps prevents BWE from starting too aggressively. */
private const val maxStartBitrateKbps = 1000L

/** Minimum target bitrate in kbps to apply start bitrate hint. Below this, the hint hurts more than it helps. */
private const val minTargetBitrateKbps = 300L

/**
 * @suppress
 */
@VisibleForTesting
fun ensureCodecBitrates(
    media: MediaDescription,
    trackBitrates: Map<TrackBitrateInfoKey, TrackBitrateInfo>,
) {
    ensureCodecBitrates(
        media = media,
        trackBitrates = trackBitrates,
        connectionStartBitrate = computeConnectionStartBitrate(trackBitrates.values),
    )
}

@VisibleForTesting
internal fun ensureCodecBitrates(
    media: MediaDescription,
    trackBitrates: Map<TrackBitrateInfoKey, TrackBitrateInfo>,
    connectionStartBitrate: Long?,
): Boolean {
    // Returns true when this media section maps to a local video track and has or
    // receives the connection-level start hint.
    val startBitrate = connectionStartBitrate ?: return false
    val (_, codecPayload) = findTrackCodecBitrateInfo(media, trackBitrates) ?: return false

    val fmtps = media.getFmtps()
    var fmtpFound = false
    for ((attribute, fmtp) in fmtps) {
        if (fmtp.payload == codecPayload) {
            fmtpFound = true
            if (fmtp.config.contains("x-google-start-bitrate")) {
                return true
            }
            attribute.value = "${fmtp.payload} ${fmtp.config};x-google-start-bitrate=$startBitrate"
            break
        }
    }

    if (!fmtpFound) {
        media.addAttribute(
            SdpFmtp(
                payload = codecPayload,
                config = "x-google-start-bitrate=$startBitrate",
            ).toAttributeField(),
        )
    }
    return true
}

private fun computeConnectionStartBitrate(
    mediaDescriptions: Collection<MediaDescription>,
    trackBitrates: Map<TrackBitrateInfoKey, TrackBitrateInfo>,
): Long? {
    // Use only video m-sections in the current SDP. trackBitrates can contain
    // stale entries after unpublish, and those must not affect the connection hint.
    return mediaDescriptions
        .asSequence()
        .filter { media -> media.media.mediaType == "video" }
        .mapNotNull { media -> findTrackCodecBitrateInfo(media, trackBitrates)?.trackBitrateInfo }
        .mapNotNull(::computeTrackStartBitrate)
        .maxOrNull()
}

/**
 * @suppress
 */
@VisibleForTesting
internal fun computeConnectionStartBitrate(trackBitrates: Collection<TrackBitrateInfo>): Long? {
    return trackBitrates.mapNotNull(::computeTrackStartBitrate).maxOrNull()
}

private data class TrackCodecBitrateInfo(
    val trackBitrateInfo: TrackBitrateInfo,
    val codecPayload: Long,
)

private fun findTrackCodecBitrateInfo(
    media: MediaDescription,
    trackBitrates: Map<TrackBitrateInfoKey, TrackBitrateInfo>,
): TrackCodecBitrateInfo? {
    val msid = media.getMsid()?.value ?: return null
    for ((key, trackBitrateInfo) in trackBitrates) {
        if (key !is TrackBitrateInfoKey.Cid) {
            continue
        }
        if (!msid.contains(key.value)) {
            continue
        }
        val (_, rtp) = media.getRtps()
            .firstOrNull { (_, rtp) -> rtp.codec.equals(trackBitrateInfo.codec, ignoreCase = true) }
            ?: continue
        return TrackCodecBitrateInfo(
            trackBitrateInfo = trackBitrateInfo,
            codecPayload = rtp.payload,
        )
    }
    return null
}

private fun computeTrackStartBitrate(trackBr: TrackBitrateInfo): Long? {
    if (trackBr.targetBitrateKbps < minTargetBitrateKbps) {
        return null
    }

    // TODO: dynamically adjust start bitrate based on network conditions, such as
    // using the previous BWE estimate.
    val calculatedStartBitrate = (trackBr.targetBitrateKbps * startBitrateMultiplier).roundToLong()
    return if (trackBr.isScreenShare) {
        calculatedStartBitrate
    } else {
        minOf(calculatedStartBitrate, maxStartBitrateKbps)
    }
}

internal fun isSVCCodec(codec: String?): Boolean {
    return codec != null &&
        ("av1".equals(codec, ignoreCase = true) ||
            "vp9".equals(codec, ignoreCase = true))
}

/**
 * @suppress
 */
data class TrackBitrateInfo(
    val codec: String,
    val targetBitrateKbps: Long,
    val isScreenShare: Boolean = false,
)

/**
 * @suppress
 */
sealed class TrackBitrateInfoKey {
    data class Cid(val value: String) : TrackBitrateInfoKey()
    data class Transceiver(val value: RtpTransceiver) : TrackBitrateInfoKey()
}
