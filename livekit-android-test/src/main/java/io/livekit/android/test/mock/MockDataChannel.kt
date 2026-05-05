/*
 * Copyright 2023-2026 LiveKit, Inc.
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

package io.livekit.android.test.mock

import livekit.org.webrtc.DataChannel

class MockDataChannel(private val label: String?) : DataChannel(1L) {

    var observer: Observer? = null
    var sentBuffers = mutableListOf<Buffer>()

    /** Snapshot of the bytes visible at send time, captured via the Buffer's current position/limit. */
    var sentPayloads = mutableListOf<ByteArray>()
    var sendResult = true

    /**
     * When true, [send] advances the buffer's position to its limit, mirroring
     * the real WebRTC wrapper which drains the buffer via `ByteBuffer.get(byte[])`.
     * Off by default to keep existing tests that read from `sentBuffers` working.
     */
    var consumeSentBuffer = false

    private var stateBacking: State = State.OPEN
    var state: State
        get() {
            ensureNotDisposed()
            return stateBacking
        }
        set(value) {
            ensureNotDisposed()
            if (value == stateBacking) {
                return
            }
            stateBacking = value
            observer?.onStateChange()
        }

    private var bufferedAmountBacking: Long = 0L
    var bufferedAmount: Long
        get() {
            ensureNotDisposed()
            return bufferedAmountBacking
        }
        set(value) {
            if (value == bufferedAmountBacking) {
                return
            }
            val previous = bufferedAmountBacking
            bufferedAmountBacking = value
            observer?.onBufferedAmountChange(previous)
        }

    var isDisposed = false

    fun clearSentBuffers() {
        sentBuffers.clear()
        sentPayloads.clear()
    }

    override fun registerObserver(observer: Observer?) {
        ensureNotDisposed()
        this.observer = observer
    }

    override fun unregisterObserver() {
        ensureNotDisposed()
        this.observer = null
    }

    override fun label(): String? {
        ensureNotDisposed()
        return label
    }

    override fun id(): Int {
        ensureNotDisposed()
        return 0
    }

    override fun state(): State {
        ensureNotDisposed()
        return state
    }

    override fun bufferedAmount(): Long {
        ensureNotDisposed()
        return bufferedAmount
    }

    override fun send(buffer: Buffer): Boolean {
        ensureNotDisposed()
        sentBuffers.add(buffer)
        // Capture what native WebRTC would receive at this moment (position..limit).
        val savedPos = buffer.data.position()
        val payload = ByteArray(buffer.data.remaining())
        buffer.data.get(payload)
        sentPayloads.add(payload)
        if (!consumeSentBuffer) {
            buffer.data.position(savedPos)
        }
        return sendResult
    }

    override fun close() {
        ensureNotDisposed()
        state = State.CLOSED
    }

    override fun dispose() {
        ensureNotDisposed()
        state = State.CLOSED
        isDisposed = true
    }

    fun ensureNotDisposed() {
        if (isDisposed) {
            throw IllegalStateException("Data channel is closed!")
        }
    }
    fun simulateBufferReceived(buffer: Buffer) {
        observer?.onMessage(buffer)
    }
}
