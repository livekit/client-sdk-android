/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.webrtc

import io.livekit.android.test.BaseTest
import io.livekit.android.test.mock.MockDataChannel
import io.livekit.android.test.mock.MockRTCThreadToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import livekit.org.webrtc.DataChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class DataChannelManagerTest : BaseTest() {

    companion object {
        private val NOOP_OBSERVER = object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}

            override fun onStateChange() {}

            override fun onMessage(buffer: DataChannel.Buffer) {}
        }
    }

    @Test
    fun onMessage_forwardsToDelegate() {
        val channel = MockDataChannel("dc")
        var received: DataChannel.Buffer? = null
        val delegate = object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}

            override fun onStateChange() {}

            override fun onMessage(buffer: DataChannel.Buffer) {
                received = buffer
            }
        }
        val manager = DataChannelManager(channel, delegate, MockRTCThreadToken())
        val payload = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        val buffer = DataChannel.Buffer(payload, true)
        manager.onMessage(buffer)
        assertSame(buffer, received)
    }

    @Test
    fun onStateChange_updatesStateFromChannel() {
        val channel = MockDataChannel("dc")
        val manager = DataChannelManager(channel, NOOP_OBSERVER, MockRTCThreadToken())
        channel.registerObserver(manager)
        assertEquals(DataChannel.State.OPEN, manager.state)
        channel.state = DataChannel.State.CLOSING
        assertEquals(DataChannel.State.CLOSING, manager.state)
    }

    @Test
    fun onBufferedAmountChange_updatesBufferedAmountFromChannel() {
        val channel = MockDataChannel("dc")
        val manager = DataChannelManager(channel, NOOP_OBSERVER, MockRTCThreadToken())
        channel.registerObserver(manager)
        channel.bufferedAmount = 12_345L
        assertEquals(12_345L, manager.bufferedAmount)
    }

    @Test
    fun dispose_closesDataChannelOnce() {
        val channel = MockDataChannel("dc")
        val manager = DataChannelManager(channel, NOOP_OBSERVER, MockRTCThreadToken())
        manager.dispose()
        assertTrue(manager.disposed)
    }

    @Test
    fun dispose_secondCallIsNoOp() {
        val channel = MockDataChannel("dc")
        val manager = DataChannelManager(channel, NOOP_OBSERVER, MockRTCThreadToken())
        manager.dispose()
        manager.dispose()
    }

    @Test
    fun waitForBufferedAmountLow_completesWhenAlreadyBelowThreshold() = runTest {
        val channel = MockDataChannel("dc")
        val manager = DataChannelManager(channel, NOOP_OBSERVER, MockRTCThreadToken())
        channel.registerObserver(manager)
        channel.bufferedAmount = 100L
        manager.waitForBufferedAmountLow(15_000L)
    }

    @Test
    fun waitForBufferedAmountLow_completesWhenAmountDrops() = runTest {
        val channel = MockDataChannel("dc")
        val manager = DataChannelManager(channel, NOOP_OBSERVER, MockRTCThreadToken())
        channel.registerObserver(manager)
        channel.bufferedAmount = 25_000L
        val waiter = async { manager.waitForBufferedAmountLow(15_000L) }
        yield()
        channel.bufferedAmount = 0L
        waiter.await()
    }

    @Test
    fun waitForBufferedAmountLow_cancelledWhenDisposedWhileWaiting() = runTest {
        val channel = MockDataChannel("dc")
        val manager = DataChannelManager(channel, NOOP_OBSERVER, MockRTCThreadToken())
        channel.registerObserver(manager)
        channel.bufferedAmount = 100_000L
        val waiter = async { manager.waitForBufferedAmountLow(15_000L) }
        yield()
        manager.dispose()
        try {
            waiter.await()
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }
}
