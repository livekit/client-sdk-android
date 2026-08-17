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

import io.livekit.android.util.LKLog
import io.livekit.android.webrtc.DataChannelManager
import livekit.org.webrtc.DataChannel
import java.nio.ByteBuffer

/**
 * The slice of the RTC data channel the outbound drain drives — a seam so the drain logic is
 * unit-testable ([livekit.org.webrtc.DataChannel] can't be constructed without a live peer
 * connection).
 *
 * @suppress
 */
internal interface DataTrackSendChannel {
    val bufferedAmount: Long
    val isOpen: Boolean
    fun send(packet: ByteArray): Boolean
}

/**
 * [DataTrackSendChannel] backed by the publisher `_data_track` [DataChannelManager].
 *
 * [bufferedAmount] is read live from the native channel so the pump can meter after each send;
 * [DataChannelManager.bufferedAmount] only updates on the buffered-amount callback.
 *
 * @suppress
 */
internal class DataChannelManagerSendChannel(
    private val manager: DataChannelManager,
) : DataTrackSendChannel {
    override val bufferedAmount: Long
        get() = manager.dataChannel.bufferedAmount()

    override val isOpen: Boolean
        get() = manager.state == DataChannel.State.OPEN

    override fun send(packet: ByteArray): Boolean {
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(packet), true)
        return manager.dataChannel.send(buffer)
    }
}

/**
 * Drop-oldest outbound drain for data-track frames.
 *
 * Packets are metered into the channel on buffered-amount events instead of dumped, keeping the
 * SCTP buffer near [LOW_WATER_MARK] (so a frame of any size streams out safely) and bounding send
 * latency: at most one frame waits while another drains, and a newer frame evicts the waiting
 * one. Frames are handled whole — a partial frame is never left on the wire.
 *
 * Not thread-safe: the owner confines all calls to the RTC thread.
 *
 * @suppress
 */
internal class DataTrackFrameSender {
    companion object {
        /**
         * Resume sending when the channel buffer drains to this level; parity with
         * `DATA_TRACK_BUFFERED_AMOUNT_LOW_THRESHOLD` in rust-sdks.
         */
        const val LOW_WATER_MARK: Long = 8 * 1024
    }

    private var channel: DataTrackSendChannel? = null

    /** Freshest queued frame (capacity one — a newer frame evicts it). */
    private var pendingFrame: List<ByteArray>? = null

    /** Packets of the frame currently draining, in FIFO order. */
    private val inFlight = ArrayDeque<ByteArray>()

    private var pumping = false

    /**
     * Attaches the channel this sender drains into, dropping frames queued for the previous one
     * (they belong to a torn-down transport).
     */
    fun attach(channel: DataTrackSendChannel?) {
        this.channel = channel
        pendingFrame = null
        inFlight.clear()
    }

    /**
     * Queues a frame's packets for sending, evicting a previously queued frame (drop-oldest).
     */
    fun sendOrQueue(packets: List<ByteArray>) {
        if (packets.isEmpty()) {
            return
        }
        val evicted = pendingFrame
        if (evicted != null) {
            LKLog.d { "Evicted queued data track frame (${evicted.size} packets) in favor of a newer one" }
        }
        pendingFrame = packets.map { it.copyOf() }
        pump()
    }

    /**
     * Feeds packets to the channel while it has headroom, promoting the queued frame when the
     * in-flight one is fully handed off.
     */
    fun pump() {
        if (pumping) {
            return
        }
        pumping = true
        try {
            val channel = channel ?: return
            if (!channel.isOpen) {
                return
            }
            while (channel.bufferedAmount <= LOW_WATER_MARK) {
                if (inFlight.isEmpty()) {
                    val next = pendingFrame ?: return
                    pendingFrame = null
                    inFlight.addAll(next)
                }
                val packet = inFlight.firstOrNull() ?: return
                if (!channel.send(packet)) {
                    LKLog.d { "Data track channel rejected packet; dropping the rest of the frame" }
                    inFlight.clear()
                    return
                }
                inFlight.removeFirst()
            }
        } finally {
            pumping = false
        }
    }
}
