package io.livekit.android.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.coroutines.TestCoroutineRule
import io.livekit.android.mock.MockEglBase
import io.livekit.android.room.participant.LocalParticipant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runBlockingTest
import livekit.LivekitModels
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.stub
import org.robolectric.RobolectricTestRunner
import org.webrtc.EglBase

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomTest {

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()
    @get:Rule
    var coroutineRule = TestCoroutineRule()

    lateinit var context: Context

    @Mock
    lateinit var rtcEngine: RTCEngine

    var eglBase: EglBase = MockEglBase()

    val localParticantFactory = object : LocalParticipant.Factory {
        override fun create(info: LivekitModels.ParticipantInfo): LocalParticipant {
            return Mockito.mock(LocalParticipant::class.java)
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
        rtcEngine.stub {
            onBlocking { rtcEngine.join(any(), any(), anyOrNull()) }
                .doReturn(SignalClientTest.JOIN.join)
        }
        val job = coroutineRule.scope.launch {
            room.connect(
                url = "http://www.example.com",
                token = "",
                options = null
            )
        }
        runBlockingTest {
            job.join()
        }
    }
}