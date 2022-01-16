package io.livekit.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.coroutines.TestCoroutineRule
import io.livekit.android.mock.MockWebSocketFactory
import io.livekit.android.mock.dagger.DaggerTestLiveKitComponent
import io.livekit.android.mock.dagger.TestCoroutinesModule
import io.livekit.android.mock.dagger.TestLiveKitComponent
import io.livekit.android.room.Room
import io.livekit.android.room.SignalClientTest
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Rule
import org.mockito.junit.MockitoJUnit

@ExperimentalCoroutinesApi
abstract class MockE2ETest {

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()

    @get:Rule
    var coroutineRule = TestCoroutineRule()

    lateinit var component: TestLiveKitComponent
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

    fun connect() {
        val job = coroutineRule.scope.launch {
            room.connect(
                url = "http://www.example.com",
                token = "",
            )
        }
        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.JOIN.toOkioByteString())

        // PeerTransport negotiation is on a debounce delay.
        coroutineRule.dispatcher.advanceTimeBy(1000L)
        runBlockingTest {
            job.join()
        }
    }
}