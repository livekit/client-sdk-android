/*
 * Copyright 2023 LiveKit, Inc.
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

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.github.ggarber.sdpparser.SdpExt
import io.github.ggarber.sdpparser.SdpFmtp
import io.github.ggarber.sdpparser.SdpMedia
import io.github.ggarber.sdpparser.SdpParser
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.util.*
import io.livekit.android.util.Either
import io.livekit.android.util.LKLog
import io.livekit.android.util.debounce
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.*
import org.webrtc.PeerConnection.RTCConfiguration
import javax.inject.Named

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
) {
    private val coroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())
    internal val peerConnection: PeerConnection = connectionFactory.createPeerConnection(
        config,
        pcObserver,
    ) ?: throw IllegalStateException("peer connection creation failed?")
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var restartingIce: Boolean = false

    private var renegotiate = false

    private val mutex = Mutex()

    private var trackBitrates = mutableMapOf<Any, TrackBitrateInfo>()

    interface Listener {
        fun onOffer(sd: SessionDescription)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        runBlocking {
            mutex.withLock {
                if (peerConnection.remoteDescription != null && !restartingIce) {
                    peerConnection.addIceCandidate(candidate)
                } else {
                    pendingCandidates.add(candidate)
                }
            }
        }
    }

    suspend fun setRemoteDescription(sd: SessionDescription): Either<Unit, String?> {
        val result = peerConnection.setRemoteDescription(sd)
        if (result is Either.Left) {
            mutex.withLock {
                pendingCandidates.forEach { pending ->
                    peerConnection.addIceCandidate(pending)
                }
                pendingCandidates.clear()
                restartingIce = false
            }
        }

        if (this.renegotiate) {
            this.renegotiate = false
            this.createAndSendOffer()
        }

        return result
    }

    val negotiate = debounce<MediaConstraints?, Unit>(100, coroutineScope) {
        if (it != null) {
            createAndSendOffer(it)
        } else {
            createAndSendOffer()
        }
    }

    suspend fun createAndSendOffer(constraints: MediaConstraints = MediaConstraints()) {
        if (listener == null) {
            return
        }

        val iceRestart =
            constraints.findConstraint(MediaConstraintKeys.ICE_RESTART) == MediaConstraintKeys.TRUE
        if (iceRestart) {
            LKLog.d { "restarting ice" }
            restartingIce = true
        }

        if (this.peerConnection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            // we're waiting for the peer to accept our offer, so we'll just wait
            // the only exception to this is when ICE restart is needed
            val curSd = peerConnection.remoteDescription
            if (iceRestart && curSd != null) {
                // TODO: handle when ICE restart is needed but we don't have a remote description
                // the best thing to do is to recreate the peerconnection
                peerConnection.setRemoteDescription(curSd)
            } else {
                renegotiate = true
                return
            }
        }

        // actually negotiate
        LKLog.d { "starting to negotiate" }
        val sdpOffer = when (val outcome = peerConnection.createOffer(constraints)) {
            is Either.Left -> outcome.value
            is Either.Right -> {
                LKLog.d { "error creating offer: ${outcome.value}" }
                return
            }
        }

        // munge sdp
        val sdpDescription = SdpParser.parse(sdpOffer.description)

        val mediaDescs = sdpDescription.media
        for (media in mediaDescs) {
            LKLog.e { media.toString() }
            if (media.mline?.type == "audio") {
                //TODO
            } else if (media.mline?.type == "video") {
                ensureVideoDDExtensionForSVC(media)
                ensureCodecBitrates(media, trackBitrates = trackBitrates)
            }

        }

        setMungedSdp(sdpOffer, sdpDescription.write())
        listener.onOffer(sdpOffer)
    }

    private suspend fun setMungedSdp(sdp: SessionDescription, mungedDescription: String, remote: Boolean = false) {
        val mungedSdp = SessionDescription(sdp.type, mungedDescription)

        LKLog.e { "sdp type: ${sdp.type}\ndescription:\n${sdp.description}" }
        LKLog.e { "munged sdp type: ${mungedSdp.type}\ndescription:\n${mungedSdp.description}" }
        val mungedResult = if (remote) {
            peerConnection.setRemoteDescription(mungedSdp)
        } else {
            peerConnection.setLocalDescription(mungedSdp)
        }


        val mungedErrorMessage = when (mungedResult) {
            is Either.Left -> {
                // munged sdp set successfully.
                return
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

        val result = if (remote) {
            peerConnection.setRemoteDescription(sdp)
        } else {
            peerConnection.setLocalDescription(sdp)
        }

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
    }

    fun prepareForIceRestart() {
        restartingIce = true
    }

    fun close() {
        peerConnection.close()
    }

    fun updateRTCConfig(config: RTCConfiguration) {
        peerConnection.setConfiguration(config)
    }

    fun registerTrackBitrateInfo(cid: String, trackBitrateInfo: TrackBitrateInfo) {
        trackBitrates[TrackBitrateInfoKey.Cid(cid)] = trackBitrateInfo
    }

    fun registerTrackBitrateInfo(transceiver: RtpTransceiver, trackBitrateInfo: TrackBitrateInfo) {
        trackBitrates[TrackBitrateInfoKey.Transceiver(transceiver)] = trackBitrateInfo
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

internal fun ensureVideoDDExtensionForSVC(mediaDesc: SdpMedia) {
    LKLog.e { mediaDesc.toString() }

    val codec = mediaDesc.rtp.firstOrNull()?.codec ?: return
    if (!isSVCCodec(codec)) {
        return
    }

    var maxId = 0L

    val ddFound = mediaDesc.ext.any { ext ->
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
        mediaDesc.ext.add(
            SdpExt(
                value = maxId + 1,
                uri = DD_EXTENSION_URI,
                config = null,
                direction = null,
                encryptUri = null,
            ),
        )
    }
}

/* The svc codec (av1/vp9) would use a very low bitrate at the begining and
increase slowly by the bandwidth estimator until it reach the target bitrate. The
process commonly cost more than 10 seconds cause subscriber will get blur video at
the first few seconds. So we use a 70% of target bitrate here as the start bitrate to
eliminate this issue.
*/
private const val startBitrateForSVC = 0.7;

internal fun ensureCodecBitrates(
    media: SdpMedia,
    trackBitrates: MutableMap<Any, TrackBitrateInfo>,
) {
    val msid = media.msid?.value ?: return
    for ((key, trackBr) in trackBitrates) {
        if (key !is TrackBitrateInfoKey.Cid) {
            continue
        }

        val (cid) = key
        if (!msid.contains(cid)) {
            continue
        }

        val rtp = media.rtp
            .firstOrNull { rtp -> rtp.codec.equals(trackBr.codec, ignoreCase = true) }
            ?: continue
        val codecPayload = rtp.payload

        var fmtpFound = false
        var replaceFmtp: SdpFmtp? = null
        var replaceFmtpWith: SdpFmtp? = null
        for (fmtp in media.fmtp) {
            if (fmtp.payload == codecPayload) {
                fmtpFound = true
                var newFmtp = fmtp
                if (!fmtp.config.contains("x-google-start-bitrate")) {
                    newFmtp = newFmtp.copy(
                        config = "${newFmtp.config};x-google-start-bitrate=${trackBr.maxBitrate * startBitrateForSVC}",
                    )
                }
                if (!fmtp.config.contains("x-google-max-bitrate")) {
                    newFmtp = newFmtp.copy(
                        config = "${newFmtp.config};x-google-max-bitrate=${trackBr.maxBitrate}",
                    )
                }
                if (fmtp != newFmtp) {
                    replaceFmtp = fmtp
                    replaceFmtpWith = newFmtp
                    break
                }
            }
        }

        if (fmtpFound) {
            if (replaceFmtp != null && replaceFmtpWith != null) {
                val index = media.fmtp.indexOf(replaceFmtp)
                if (media.fmtp.remove(replaceFmtp)) {
                    media.fmtp.add(index, replaceFmtpWith)
                }
            }
        } else {
            media.fmtp.add(
                SdpFmtp(
                    payload = codecPayload,
                    config = "x-google-start-bitrate=${trackBr.maxBitrate * startBitrateForSVC};" +
                        "x-google-max-bitrate=${trackBr.maxBitrate}",
                ),
            )
        }
    }
}

internal fun isSVCCodec(codec: String): Boolean {
    return "av1".equals(codec, ignoreCase = true) ||
        "vp9".equals(codec, ignoreCase = true)
}

internal data class TrackBitrateInfo(
    val codec: String,
    val maxBitrate: Long,
)

sealed class TrackBitrateInfoKey {
    data class Cid(val value: String)
    data class Transceiver(val value: RtpTransceiver)
}
