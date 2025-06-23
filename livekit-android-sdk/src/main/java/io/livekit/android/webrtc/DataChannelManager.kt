/*
 * Copyright 2025 LiveKit, Inc.
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

import io.livekit.android.coroutines.cancelOnSignal
import io.livekit.android.room.RTCEngine
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.flow
import io.livekit.android.util.flowDelegate
import io.livekit.android.webrtc.peerconnection.executeOnRTCThread
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import livekit.org.webrtc.DataChannel

/**
 * @suppress
 */
class DataChannelManager(
    val dataChannel: DataChannel,
    private val dataMessageListener: DataChannel.Observer,
) : DataChannel.Observer {

    @get:FlowObservable
    var disposed by flowDelegate(false)
        private set

    @get:FlowObservable
    var bufferedAmount by flowDelegate(0L)
        private set

    @get:FlowObservable
    var state by flowDelegate(dataChannel.state())
        private set

    suspend fun waitForBufferedAmountLow(amount: Long = RTCEngine.MAX_DATA_PACKET_SIZE.toLong()) {
        val signal = ::disposed.flow.map { if (it) Unit else null }

        ::bufferedAmount.flow
            .cancelOnSignal(signal)
            .takeWhile { it > amount }
            .collect()
    }

    override fun onBufferedAmountChange(previousAmount: Long) {
        bufferedAmount = dataChannel.bufferedAmount()
    }

    override fun onStateChange() {
        state = dataChannel.state()
    }

    override fun onMessage(buffer: DataChannel.Buffer) {
        dataMessageListener.onMessage(buffer)
    }

    fun dispose() {
        synchronized(this) {
            if (disposed) {
                return
            }
            disposed = true
            bufferedAmount = 0
        }
        executeOnRTCThread {
            dataChannel.unregisterObserver()
            dataChannel.close()
            dataChannel.dispose()
        }
    }

    interface Listener {
        fun onBufferedAmountChange(dataChannel: DataChannel, newAmount: Long, previousAmount: Long)
        fun onStateChange(dataChannel: DataChannel, state: DataChannel.State)
        fun onMessage(dataChannel: DataChannel, buffer: DataChannel.Buffer)
    }
}
