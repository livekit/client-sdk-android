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

import io.livekit.android.util.LKLog
import io.livekit.android.webrtc.peerconnection.RTC_EXECUTOR_THREADNAME_PREFIX
import livekit.org.webrtc.PeerConnectionFactory

/**
 * @suppress
 */
data class PeerConnectionFactoryManager(val peerConnectionFactory: PeerConnectionFactory) {
    var isDisposed = false
        private set

    /**
     * Must only be called on the RTC thread
     */
    fun dispose() {
        if (isDisposed) {
            LKLog.w { "Calling dispose multiple times on PeerConnectionFactory?" }
            return
        }

        val thread = Thread.currentThread()
        if (!thread.name.startsWith(RTC_EXECUTOR_THREADNAME_PREFIX)) {
            throw IllegalStateException("PeerConnectionFactory must be disposed on the RTC thread!")
        }

        isDisposed = true
        peerConnectionFactory.dispose()
    }
}
