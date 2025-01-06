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

package io.livekit.android.test.mock

import livekit.org.webrtc.DataChannel

class MockDataChannel(private val label: String?) : DataChannel(1L) {

    var observer: Observer? = null
    var sentBuffers = mutableListOf<Buffer>()
    override fun registerObserver(observer: Observer?) {
        this.observer = observer
    }

    override fun unregisterObserver() {
        observer = null
    }

    override fun label(): String? {
        return label
    }

    override fun id(): Int {
        return 0
    }

    override fun state(): State {
        return State.OPEN
    }

    override fun bufferedAmount(): Long {
        return 0
    }

    override fun send(buffer: Buffer): Boolean {
        sentBuffers.add(buffer)
        return true
    }

    override fun close() {
    }

    override fun dispose() {
    }

    fun simulateBufferReceived(buffer: Buffer) {
        observer?.onMessage(buffer)
    }
}
