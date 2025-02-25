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

import livekit.org.webrtc.MockRtpParameters
import livekit.org.webrtc.RtpParameters
import livekit.org.webrtc.RtpSender
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.UUID

object MockRtpSender {
    fun create(id: String = "sender_id"): RtpSender {
        var rtpParameters: RtpParameters = MockRtpParameters(
            transactionId = UUID.randomUUID().toString(),
            degradationPreference = null,
            rtcp = MockRtpParameters.MockRtcp("", false),
            headerExtensions = mutableListOf(),
            encodings = mutableListOf(),
            codecs = mutableListOf(),
        )
        return Mockito.mock(RtpSender::class.java).apply {
            whenever(this.parameters).thenAnswer { rtpParameters }
            whenever(this.id()).thenReturn(id)
            whenever(this.setParameters(any())).thenAnswer {
                rtpParameters = it.getArgument(0)
                true
            }
        }
    }
}
