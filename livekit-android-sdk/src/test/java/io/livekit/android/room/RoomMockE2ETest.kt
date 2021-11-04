package io.livekit.android.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.coroutines.TestCoroutineRule
import io.livekit.android.mock.MockWebsocketFactory
import io.livekit.android.mock.dagger.DaggerTestLiveKitComponent
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomMockE2ETest {

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()

    @get:Rule
    var coroutineRule = TestCoroutineRule()

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

    fun connect() {
        val job = coroutineRule.scope.launch {
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

    @Test
    fun connectTest() {
        connect()
    }

    @Test
    fun roomUpdateTest() {
        connect()
        wsFactory.listener.onMessage(wsFactory.ws, SignalClientTest.ROOM_UPDATE.toOkioByteString())

        Assert.assertEquals(
            SignalClientTest.ROOM_UPDATE.roomUpdate.room.metadata,
            room.metadata
        )
    }

    @Test
    fun connectionQualityUpdateTest() {
        val roomListener = Mockito.mock(RoomListener::class.java)
        room.listener = roomListener

        connect()
        wsFactory.listener.onMessage(
            wsFactory.ws,
            SignalClientTest.CONNECTION_QUALITY.toOkioByteString()
        )

        Assert.assertEquals(
            ConnectionQuality.EXCELLENT,
            room.localParticipant.connectionQuality
        )
        Mockito.verify(roomListener)
            .onConnectionQualityChanged(room.localParticipant, ConnectionQuality.EXCELLENT)
    }
}