package io.livekit.android.room

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
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
import javax.inject.Named

/**
 * @suppress
 */
internal class PeerConnectionTransport
@AssistedInject
constructor(
    @Assisted config: PeerConnection.RTCConfiguration,
    @Assisted pcObserver: PeerConnection.Observer,
    @Assisted private val listener: Listener?,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    connectionFactory: PeerConnectionFactory
) {
    private val coroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())
    internal val peerConnection: PeerConnection = connectionFactory.createPeerConnection(
        config,
        pcObserver
    ) ?: throw IllegalStateException("peer connection creation failed?")
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var restartingIce: Boolean = false

    private var renegotiate = false

    private val mutex = Mutex()

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

        LKLog.v { "sdp offer = $sdpOffer, description: ${sdpOffer.description}, type: ${sdpOffer.type}" }
        peerConnection.setLocalDescription(sdpOffer)
        listener?.onOffer(sdpOffer)
    }


    fun prepareForIceRestart() {
        restartingIce = true
    }

    fun close() {
        peerConnection.close()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            config: PeerConnection.RTCConfiguration,
            pcObserver: PeerConnection.Observer,
            listener: Listener?
        ): PeerConnectionTransport
    }
}