package io.livekit.android.mock

import io.livekit.android.room.PeerConnectionTransport
import kotlinx.coroutines.CoroutineDispatcher
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

internal class MockPeerConnectionTransportFactory(
    private val dispatcher: CoroutineDispatcher,
) : PeerConnectionTransport.Factory {
    override fun create(
        config: PeerConnection.RTCConfiguration,
        pcObserver: PeerConnection.Observer,
        listener: PeerConnectionTransport.Listener?
    ): PeerConnectionTransport {
        return PeerConnectionTransport(
            config,
            pcObserver,
            listener,
            dispatcher,
            PeerConnectionFactory.builder()
                .createPeerConnectionFactory()
        )
    }
}