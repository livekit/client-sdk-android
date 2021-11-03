package io.livekit.android.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.mock.MockWebsocketFactory
import io.livekit.android.mock.dagger.DaggerTestLiveKitComponent
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomMockE2ETest {

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()

    lateinit var context: Context
    lateinit var room: Room
    lateinit var wsFactory: MockWebsocketFactory

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val component = DaggerTestLiveKitComponent
            .factory()
            .create(context)

        room = component.roomFactory()
            .create(context)
        wsFactory = component.websocketFactory()
    }

    @Test
    fun connectTest() {
        val job = TestCoroutineScope().launch {
            room.connect(
                url = "http://www.example.com",
                token = "",
                options = null
            )
        }

        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.JOIN.toOkioByteString())

        runBlockingTest {
            job.join()
        }
    }
}