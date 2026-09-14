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

package io.livekit.android.room.datatrack

import io.livekit.android.test.BaseTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeSendChannel : DataTrackSendChannel {
    override var bufferedAmount: Long = 0
    override var isOpen = true
    var acceptsSends = true
    val sent = mutableListOf<ByteArray>()

    override fun send(packet: ByteArray): Boolean {
        if (!acceptsSends) {
            return false
        }
        sent.add(packet)
        bufferedAmount += packet.size
        return true
    }

    /** Simulates the transport flushing its buffer (the trigger for a buffered-amount callback). */
    fun drain() {
        bufferedAmount = 0
    }
}

/**
 * Pins the outbound drain's semantics, which are deliberately aligned (and deliberately not)
 * with the other SDKs:
 *
 * - **rust-sdks** (`DataChannelSender`): the same design — drop-oldest with a capacity-one frame
 *   queue, whole-frame atomicity, packets metered on buffered-amount events with an 8 KiB
 *   low-water mark. These tests mirror its invariants.
 * - **client-sdk-js** (`LossyDataChannel` with `bufferFullBehavior: 'wait'`): shares the
 *   whole-frame atomicity and watermark pacing, but blocks the producer under load instead of
 *   dropping — its engine awaits sends, so overload backpressures the frame producer. Android's
 *   producer is a fire-and-forget FFI callback with no backpressure channel, so freshest-wins
 *   eviction is used instead (as in rust-sdks / Swift).
 */
class DataTrackFrameSenderTest : BaseTest() {

    private lateinit var channel: FakeSendChannel
    private lateinit var sender: DataTrackFrameSender

    @Before
    fun setUpSender() {
        channel = FakeSendChannel()
        sender = DataTrackFrameSender()
        sender.attach(channel)
    }

    @Test
    fun sendsImmediatelyWithHeadroom() {
        sender.sendOrQueue(frame(1, packets = 3))
        assertEquals(3, channel.sent.size)
    }

    /**
     * The whole frame goes out even when it is far larger than the buffer headroom: packets are
     * metered per drain instead of dumped, so there is no sender-imposed max frame size.
     */
    @Test
    fun largeFrameStreamsWithinHeadroom() {
        val packetSize = 64000
        sender.sendOrQueue(frame(1, packets = 50, packetSize = packetSize))
        var pumps = 0
        while (channel.sent.size < 50 && pumps < 100) {
            // Each drain admits exactly one over-watermark packet, so the buffer never holds more
            // than one packet beyond the low-water mark.
            assertTrue(channel.bufferedAmount <= DataTrackFrameSender.LOW_WATER_MARK + packetSize)
            channel.drain()
            sender.pump()
            pumps++
        }
        assertEquals(50, channel.sent.size)
    }

    /**
     * A newer frame evicts the queued (not yet started) one — freshest wins.
     */
    @Test
    fun dropsOldestQueuedFrame() {
        channel.bufferedAmount = DataTrackFrameSender.LOW_WATER_MARK + 1
        sender.sendOrQueue(frame(1))
        sender.sendOrQueue(frame(2))
        assertTrue(channel.sent.isEmpty())

        channel.drain()
        sender.pump()
        assertEquals(listOf(2.toByte()), channel.sent.map { it.first() })
    }

    /**
     * An in-flight frame is never abandoned mid-send: its remaining packets go out before a
     * newer frame, and packets of two frames never interleave.
     */
    @Test
    fun inFlightFrameCompletesBeforeNewerFrame() {
        sender.sendOrQueue(frame(1, packets = 3, packetSize = 64000))
        assertEquals(1, channel.sent.size)

        sender.sendOrQueue(frame(2, packets = 2, packetSize = 64000))
        while (channel.sent.size < 5) {
            channel.drain()
            sender.pump()
        }
        assertEquals(
            listOf(1.toByte(), 1.toByte(), 1.toByte(), 2.toByte(), 2.toByte()),
            channel.sent.map { it.first() },
        )
    }

    /**
     * Attaching a channel drops frames queued for the previous one (stale frames belong to a
     * dead transport).
     */
    @Test
    fun attachClearsQueuedFrames() {
        channel.bufferedAmount = DataTrackFrameSender.LOW_WATER_MARK + 1
        sender.sendOrQueue(frame(1))

        val newChannel = FakeSendChannel()
        sender.attach(newChannel)
        sender.pump()
        assertTrue(newChannel.sent.isEmpty())

        sender.sendOrQueue(frame(2))
        assertEquals(listOf(2.toByte()), newChannel.sent.map { it.first() })
    }

    /** A rejected send drops the rest of the frame without wedging the pump. */
    @Test
    fun rejectedSendDropsFrameOnly() {
        channel.acceptsSends = false
        sender.sendOrQueue(frame(1, packets = 3))
        assertTrue(channel.sent.isEmpty())

        channel.acceptsSends = true
        sender.sendOrQueue(frame(2))
        assertEquals(listOf(2.toByte()), channel.sent.map { it.first() })
    }

    /** An empty packet batch must not evict a queued frame. */
    @Test
    fun emptyBatchIsIgnored() {
        channel.bufferedAmount = DataTrackFrameSender.LOW_WATER_MARK + 1
        sender.sendOrQueue(frame(1))
        sender.sendOrQueue(emptyList())

        channel.drain()
        sender.pump()
        assertEquals(listOf(1.toByte()), channel.sent.map { it.first() })
    }

    /** Nothing is sent while the channel is closed; opening drains the queue. */
    @Test
    fun queuedFrameDrainsOnceOpen() {
        channel.isOpen = false
        sender.sendOrQueue(frame(1))
        assertTrue(channel.sent.isEmpty())

        channel.isOpen = true
        sender.pump()
        assertEquals(listOf(1.toByte()), channel.sent.map { it.first() })
    }

    companion object {
        /** One packet per frame, tagged for identification. */
        private fun frame(tag: Byte, packets: Int = 1, packetSize: Int = 100): List<ByteArray> =
            List(packets) { ByteArray(packetSize) { tag } }
    }
}
