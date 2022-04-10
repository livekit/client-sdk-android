package io.livekit.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.MessageLite
import io.livekit.android.mock.MockPeerConnection
import io.livekit.android.mock.MockWebSocketFactory
import io.livekit.android.mock.dagger.DaggerTestLiveKitComponent
import io.livekit.android.mock.dagger.TestCoroutinesModule
import io.livekit.android.mock.dagger.TestLiveKitComponent
import io.livekit.android.room.PeerConnectionTransport
import io.livekit.android.room.Room
import io.livekit.android.room.SignalClientTest
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import org.junit.Before
import org.webrtc.PeerConnection

@ExperimentalCoroutinesApi
abstract class MockE2ETest : BaseTest() {

    internal lateinit var component: TestLiveKitComponent
    internal lateinit var context: Context
    internal lateinit var room: Room
    internal lateinit var wsFactory: MockWebSocketFactory
    internal lateinit var subscriber: PeerConnectionTransport

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        component = DaggerTestLiveKitComponent
            .factory()
            .create(context, TestCoroutinesModule(coroutineRule.dispatcher))

        room = component.roomFactory()
            .create(context)
        wsFactory = component.websocketFactory()
    }

    suspend fun connect() {
        connectSignal()
        connectPeerConnection()
    }

    suspend fun connectSignal() {
        val job = coroutineRule.scope.launch {
            room.connect(
                url = SignalClientTest.EXAMPLE_URL,
                token = "",
            )
        }
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.JOIN.toOkioByteString())

        job.join()
    }

    suspend fun connectPeerConnection() {
        subscriber = component.rtcEngine().subscriber
        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.OFFER.toOkioByteString())
        val subPeerConnection = subscriber.peerConnection as MockPeerConnection
        subPeerConnection.moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
    }

    fun createOpenResponse(request: Request): Response {
        return Response.Builder()
            .request(request)
            .code(200)
            .protocol(Protocol.HTTP_2)
            .message("")
            .build()
    }

    /**
     * Simulates receiving [message] from the server
     */
    fun simulateMessageFromServer(message: MessageLite) {
        simulateMessageFromServer(message.toOkioByteString())
    }

    /**
     * Simulates receiving [message] from the server
     */
    fun simulateMessageFromServer(message: ByteString) {
        wsFactory.listener.onMessage(wsFactory.ws, message)
    }
}