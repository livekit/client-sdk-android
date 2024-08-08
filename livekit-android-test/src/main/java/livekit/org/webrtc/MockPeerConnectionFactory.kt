/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package livekit.org.webrtc

import io.livekit.android.test.mock.MockAudioSource
import io.livekit.android.test.mock.MockAudioStreamTrack
import io.livekit.android.test.mock.MockPeerConnection
import io.livekit.android.test.mock.MockVideoSource
import io.livekit.android.test.mock.MockVideoStreamTrack

class MockPeerConnectionFactory : PeerConnectionFactory(1L) {
    override fun createPeerConnectionInternal(
        rtcConfig: PeerConnection.RTCConfiguration,
        constraints: MediaConstraints?,
        observer: PeerConnection.Observer?,
        sslCertificateVerifier: SSLCertificateVerifier?,
    ): PeerConnection {
        return MockPeerConnection(rtcConfig, observer)
    }

    override fun createAudioSource(constraints: MediaConstraints?): AudioSource {
        return MockAudioSource()
    }

    override fun createAudioTrack(id: String, source: AudioSource?): AudioTrack {
        return MockAudioStreamTrack(id = id)
    }

    override fun createVideoSource(isScreencast: Boolean, alignTimestamps: Boolean): VideoSource {
        return MockVideoSource()
    }

    override fun createVideoSource(isScreencast: Boolean): VideoSource {
        return MockVideoSource()
    }

    override fun createVideoTrack(id: String, source: VideoSource?): VideoTrack {
        return MockVideoStreamTrack(id = id)
    }

    override fun getRtpSenderCapabilities(mediaType: MediaStreamTrack.MediaType): RtpCapabilities {
        return RtpCapabilities(
            listOf(
                RtpCapabilities.CodecCapability().apply {
                    name = "VP8"
                    mimeType = "video/VP8"
                    kind = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                    parameters = emptyMap()
                },
                RtpCapabilities.CodecCapability().apply {
                    name = "H264"
                    mimeType = "video/H264"
                    kind = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                    parameters = mapOf("profile-level-id" to "640c1f")
                },
                RtpCapabilities.CodecCapability().apply {
                    name = "H264"
                    mimeType = "video/H264"
                    kind = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                    parameters = mapOf("profile-level-id" to "42e01f")
                },
                RtpCapabilities.CodecCapability().apply {
                    name = "AV1"
                    mimeType = "video/AV1"
                    kind = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                    parameters = emptyMap()
                },
                RtpCapabilities.CodecCapability().apply {
                    name = "VP9"
                    mimeType = "video/VP9"
                    kind = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                    parameters = mapOf("profile-id" to "0")
                },
            ),
            emptyList(),
        )
    }
}
