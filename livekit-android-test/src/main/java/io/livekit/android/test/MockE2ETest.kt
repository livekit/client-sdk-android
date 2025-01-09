/*
 * Copyright 2023-2025 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.MessageLite
import io.livekit.android.room.Room
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.MockWebSocketFactory
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.dagger.DaggerTestLiveKitComponent
import io.livekit.android.test.mock.dagger.TestCoroutinesModule
import io.livekit.android.test.mock.dagger.TestLiveKitComponent
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import livekit.LivekitRtc
import livekit.org.webrtc.PeerConnection
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
abstract class MockE2ETest : BaseTest() {

    lateinit var component: TestLiveKitComponent
    lateinit var context: Context
    lateinit var room: Room
    lateinit var wsFactory: MockWebSocketFactory

    @Before
    fun mocksSetup() {
        context = ApplicationProvider.getApplicationContext()
        component = DaggerTestLiveKitComponent
            .factory()
            .create(context, TestCoroutinesModule(coroutineRule.dispatcher))

        room = component.roomFactory()
            .create(context)
            .apply {
                enableMetrics = false
            }
        wsFactory = component.websocketFactory()
    }

    @After
    fun tearDown() {
        room.release()
    }

    open suspend fun connect(joinResponse: LivekitRtc.SignalResponse = TestData.JOIN) {
        connectSignal(joinResponse)
        connectPeerConnection()
    }

    suspend fun connectSignal(joinResponse: LivekitRtc.SignalResponse) {
        val job = coroutineRule.scope.launch {
            room.connect(
                url = TestData.EXAMPLE_URL,
                token = "",
            )
        }
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(joinResponse)

        job.join()
    }

    fun getSubscriberPeerConnection() =
        component
            .rtcEngine()
            .getSubscriberPeerConnection() as MockPeerConnection

    fun getPublisherPeerConnection() =
        component
            .rtcEngine()
            .getPublisherPeerConnection() as MockPeerConnection

    fun connectPeerConnection() {
        simulateMessageFromServer(TestData.OFFER)
        val subPeerConnection = getSubscriberPeerConnection()
        subPeerConnection.moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
    }

    fun disconnectPeerConnection() {
        val subPeerConnection = component
            .rtcEngine()
            .getSubscriberPeerConnection() as MockPeerConnection
        subPeerConnection.moveToIceConnectionState(PeerConnection.IceConnectionState.FAILED)
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
