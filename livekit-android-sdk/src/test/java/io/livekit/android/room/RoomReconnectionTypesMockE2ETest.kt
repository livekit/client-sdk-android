package io.livekit.android.room

import io.livekit.android.MockE2ETest
import io.livekit.android.assert.assertIsClassList
import io.livekit.android.events.EventCollector
import io.livekit.android.events.FlowCollector
import io.livekit.android.events.RoomEvent
import io.livekit.android.util.flow
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(ParameterizedRobolectricTestRunner::class)
class RoomReconnectionTypesMockE2ETest(
    private val reconnectType: ReconnectType
) : MockE2ETest() {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "Input: {0}")
        // parameters are provided as arrays, allowing more than one parameter
        fun params() = listOf(
            ReconnectType.FORCE_SOFT_RECONNECT,
            ReconnectType.FORCE_FULL_RECONNECT,
        )
    }

    private fun prepareForReconnect() {
        wsFactory.onOpen = {
            wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
            val softReconnectParam = wsFactory.request.url
                .queryParameter(SignalClient.CONNECT_QUERY_RECONNECT)
                ?.toIntOrNull()
                ?: 0

            if (softReconnectParam == 0) {
                simulateMessageFromServer(SignalClientTest.JOIN)
            }
        }
    }

    @Before
    fun setup() {
        room.setReconnectionType(reconnectType)
    }

    @Test
    fun reconnectFromPeerConnectionDisconnect() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        val stateCollector = FlowCollector(room::state.flow, coroutineRule.scope)
        prepareForReconnect()
        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val events = eventCollector.stopCollecting()
        val states = stateCollector.stopCollecting()

        assertIsClassList(
            listOf(
                RoomEvent.Reconnecting::class.java,
                RoomEvent.Reconnected::class.java,
            ),
            events
        )

        assertEquals(
            listOf(
                Room.State.CONNECTED,
                Room.State.RECONNECTING,
                Room.State.CONNECTED,
            ),
            states
        )
    }

    @Test
    fun reconnectFromWebSocketFailure() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        val stateCollector = FlowCollector(room::state.flow, coroutineRule.scope)
        prepareForReconnect()
        wsFactory.ws.cancel()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val events = eventCollector.stopCollecting()
        val states = stateCollector.stopCollecting()

        assertIsClassList(
            listOf(
                RoomEvent.Reconnecting::class.java,
                RoomEvent.Reconnected::class.java,
            ),
            events
        )

        assertEquals(
            listOf(
                Room.State.CONNECTED,
                Room.State.RECONNECTING,
                Room.State.CONNECTED,
            ),
            states
        )
    }
}