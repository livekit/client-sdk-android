package io.livekit.android.room

import com.google.protobuf.ByteString
import io.livekit.android.LiveKit
import io.livekit.android.MockE2ETest
import io.livekit.android.assert.assertIsClass
import io.livekit.android.events.EventCollector
import io.livekit.android.events.RoomEvent
import io.livekit.android.mock.MockDataChannel
import io.livekit.android.mock.MockPeerConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitModels.DataPacket
import livekit.LivekitModels.UserPacket
import org.junit.Assert.assertEquals
import org.junit.Test
import org.webrtc.DataChannel
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class RoomDataMockE2ETest: MockE2ETest() {
    @Test
    fun dataReceivedEvent() = runTest {
        connect()
        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        val subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)

        val collector = EventCollector(room.events, coroutineRule.scope)
        val dataPacket = with(DataPacket.newBuilder()) {
            user = with(UserPacket.newBuilder()) {
                payload = ByteString.copyFrom("hello", Charsets.UTF_8)
                build()
            }
            build()
        }
        val dataBuffer = DataChannel.Buffer(
            ByteBuffer.wrap(dataPacket.toByteArray()),
            true
        )

        subDataChannel.observer?.onMessage(dataBuffer)
        val events = collector.stopCollecting()

        assertEquals(1, events.size)
        assertIsClass(RoomEvent.DataReceived::class.java, events[0])

        val event = events[0] as RoomEvent.DataReceived
        assertEquals("hello", event.data.decodeToString())
    }
}
