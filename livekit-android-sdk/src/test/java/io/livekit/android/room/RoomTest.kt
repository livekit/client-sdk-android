package io.livekit.android.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.room.mock.MockEglBase
import io.livekit.android.room.participant.LocalParticipant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import livekit.LivekitModels
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.robolectric.RobolectricTestRunner
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomTest {

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()

    lateinit var context: Context

    @Mock
    lateinit var rtcEngine: RTCEngine

    @Mock
    lateinit var peerConnectionFactory: PeerConnectionFactory
    var eglBase: EglBase = MockEglBase()

    val localParticantFactory = object : LocalParticipant.Factory {
        override fun create(info: LivekitModels.ParticipantInfo): LocalParticipant {
            return LocalParticipant(
                info,
                rtcEngine,
                peerConnectionFactory,
                context,
                eglBase,
            )
        }
    }

    lateinit var room: Room

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        room = Room(
            context,
            rtcEngine,
            eglBase,
            localParticantFactory
        )
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
        room.onIceConnected()
        runBlocking {
            Assert.assertNotNull(
                withTimeoutOrNull(1000) {
                    job.join()
                }
            )
        }
    }
}