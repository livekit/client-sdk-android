package io.livekit.android.room

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.webrtc.*

class PeerConnectionTransport
@AssistedInject
constructor(
    config: PeerConnection.RTCConfiguration,
    listener: PeerConnection.Observer,
    @Assisted connectionFactory: PeerConnectionFactory
) {
    val peerConnection: PeerConnection = connectionFactory.createPeerConnection(
        config,
        listener
    ) ?: throw IllegalStateException("peer connection creation failed?")
    val pendingCandidates = mutableListOf<IceCandidate>()

    fun addIceCandidate(candidate: IceCandidate) {
        if (peerConnection.remoteDescription != null) {
            peerConnection.addIceCandidate(candidate)
        } else {
            pendingCandidates.add(candidate)
        }
    }

    fun setRemoteDescription(sd: SessionDescription) {
        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
            }

            override fun onSetSuccess() {
                pendingCandidates.forEach { pending ->
                    peerConnection.addIceCandidate(pending)
                }
                pendingCandidates.clear()
            }

            override fun onCreateFailure(p0: String?) {
            }

            override fun onSetFailure(p0: String?) {
            }

        }, sd)
    }

    fun close() {
        peerConnection.close()
    }
}