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
import io.livekit.android.webrtc.peerconnection.executeBlockingOnRTCThread
import io.livekit.android.webrtc.peerconnection.launchBlockingOnRTCThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnection.RTCConfiguration
import livekit.org.webrtc.PeerConnection.SignalingState
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SessionDescription
import java.util.concurrent.atomic.AtomicBoolean
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
) {
    private val coroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())

    @VisibleForTesting
    internal val peerConnection: PeerConnection = executeBlockingOnRTCThread {
        connectionFactory.createPeerConnection(
            config,
            pcObserver,
        ) ?: throw IllegalStateException("peer connection creation failed?")
    }
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var restartingIce: Boolean = false

    private var renegotiate = false

    private var trackBitrates = mutableMapOf<TrackBitrateInfoKey, TrackBitrateInfo>()
    private var isClosed = AtomicBoolean(false)

    interface Listener {
        fun onOffer(sd: SessionDescription)
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

    suspend fun setRemoteDescription(sd: SessionDescription): Either<Unit, String?> {
        val result = launchRTCIfNotClosed {
            val result = peerConnection.setRemoteDescription(sd)
            if (result is Either.Left) {
                pendingCandidates.forEach { pending ->
                    peerConnection.addIceCandidate(pending)
                }
                pendingCandidates.clear()
                restartingIce = false
            }
            result
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

    private suspend fun createAndSendOffer(constraints: MediaConstraints = MediaConstraints()) {
        if (listener == null) {
            return
        }

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
            for (mediaDesc in mediaDescs) {
                if (mediaDesc !is MediaDescription) {
                    continue
                }
                if (mediaDesc.media.mediaType == "audio") {
                    // TODO
                } else if (mediaDesc.media.mediaType == "video") {
                    ensureVideoDDExtensionForSVC(mediaDesc)
                    ensureCodecBitrates(mediaDesc, trackBitrates = trackBitrates)
                }
            }
            finalSdp = setMungedSdp(sdpOffer, sdpDescription.toString())
        }
        if (finalSdp != null) {
            listener.onOffer(finalSdp!!)
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
        return launchBlockingOnRTCThread {
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
        return executeBlockingOnRTCThread {
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

/* The svc codec (av1/vp9) would use a very low bitrate at the beginning and
increase slowly by the bandwidth estimator until it reach the target bitrate. The
process commonly cost more than 10 seconds cause subscriber will get blur video at
the first few seconds. So we use a 70% of target bitrate here as the start bitrate to
eliminate this issue.
*/
private const val startBitrateForSVC = 0.7

/**
 * @suppress
 */
@VisibleForTesting
fun ensureCodecBitrates(
    media: MediaDescription,
    trackBitrates: Map<TrackBitrateInfoKey, TrackBitrateInfo>,
) {
    val msid = media.getMsid()?.value ?: return
    for ((key, trackBr) in trackBitrates) {
        if (key !is TrackBitrateInfoKey.Cid) {
            continue
        }

        val (cid) = key
        if (!msid.contains(cid)) {
            continue
        }

        val (_, rtp) = media.getRtps()
            .firstOrNull { (_, rtp) -> rtp.codec.equals(trackBr.codec, ignoreCase = true) }
            ?: continue
        val codecPayload = rtp.payload

        val fmtps = media.getFmtps()
        var fmtpFound = false
        for ((attribute, fmtp) in fmtps) {
            if (fmtp.payload == codecPayload) {
                fmtpFound = true
                var newFmtpConfig = fmtp.config
                if (!fmtp.config.contains("x-google-start-bitrate")) {
                    newFmtpConfig = "$newFmtpConfig;x-google-start-bitrate=${(trackBr.maxBitrate * startBitrateForSVC).roundToLong()}"
                }
                if (!fmtp.config.contains("x-google-max-bitrate")) {
                    newFmtpConfig = "$newFmtpConfig;x-google-max-bitrate=${trackBr.maxBitrate}"
                }
                if (fmtp.config != newFmtpConfig) {
                    attribute.value = "${fmtp.payload} $newFmtpConfig"
                    break
                }
            }
        }

        if (!fmtpFound) {
            media.addAttribute(
                SdpFmtp(
                    payload = codecPayload,
                    config = "x-google-start-bitrate=${trackBr.maxBitrate * startBitrateForSVC};" +
                        "x-google-max-bitrate=${trackBr.maxBitrate}",
                ).toAttributeField(),
            )
        }
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
    val maxBitrate: Long,
)

/**
 * @suppress
 */
sealed class TrackBitrateInfoKey {
    data class Cid(val value: String) : TrackBitrateInfoKey()
    data class Transceiver(val value: RtpTransceiver) : TrackBitrateInfoKey()
}
