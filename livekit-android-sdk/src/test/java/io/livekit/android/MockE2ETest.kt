package io.livekit.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.mock.MockWebSocketFactory
import io.livekit.android.mock.dagger.DaggerTestLiveKitComponent
import io.livekit.android.mock.dagger.TestCoroutinesModule
import io.livekit.android.mock.dagger.TestLiveKitComponent
import io.livekit.android.room.Room
import io.livekit.android.room.SignalClientTest
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Before

@ExperimentalCoroutinesApi
abstract class MockE2ETest : BaseTest() {

    internal lateinit var component: TestLiveKitComponent
    lateinit var context: Context
    lateinit var room: Room
    lateinit var wsFactory: MockWebSocketFactory

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

    fun createOpenResponse(request: Request): Response {
        return Response.Builder()
            .request(request)
            .code(200)
            .protocol(Protocol.HTTP_2)
            .message("")
            .build()
    }
}