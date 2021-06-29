package io.livekit.android.room

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.room.util.CoroutineSdpObserver
import io.livekit.android.util.Either
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription

/**
 * @suppress
 */
class PeerConnectionTransport
@AssistedInject
constructor(
    @Assisted config: PeerConnection.RTCConfiguration,
    @Assisted listener: PeerConnection.Observer,
    connectionFactory: PeerConnectionFactory
) {
    val peerConnection: PeerConnection = connectionFactory.createPeerConnection(
        config,
        listener
    ) ?: throw IllegalStateException("peer connection creation failed?")
    val pendingCandidates = mutableListOf<IceCandidate>()
    var iceRestart: Boolean = false

    fun addIceCandidate(candidate: IceCandidate) {
        if (peerConnection.remoteDescription != null && !iceRestart) {
            peerConnection.addIceCandidate(candidate)
        } else {
            pendingCandidates.add(candidate)
        }
    }

    suspend fun setRemoteDescription(sd: SessionDescription): Either<Unit, String?> {

        val observer = object : CoroutineSdpObserver() {
            override fun onSetSuccess() {
                pendingCandidates.forEach { pending ->
                    peerConnection.addIceCandidate(pending)
                }
                pendingCandidates.clear()
                iceRestart = false
                super.onSetSuccess()
            }
        }
        
        peerConnection.setRemoteDescription(observer, sd)
        return observer.awaitSet()
    }

    fun prepareForIceRestart() {
        iceRestart = true
    }

    fun close() {
        peerConnection.close()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            config: PeerConnection.RTCConfiguration,
            listener: PeerConnection.Observer
        ): PeerConnectionTransport
    }
}