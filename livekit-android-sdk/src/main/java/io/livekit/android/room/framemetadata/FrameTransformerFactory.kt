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

package io.livekit.android.room.framemetadata

import livekit.org.webrtc.FrameCryptor
import livekit.org.webrtc.FrameCryptorAlgorithm
import livekit.org.webrtc.FrameCryptorFactory
import livekit.org.webrtc.FrameCryptorKeyProvider
import livekit.org.webrtc.FrameMetadataTransformer
import livekit.org.webrtc.FrameMetadataTransformerFactory
import livekit.org.webrtc.FrameTransformer
import livekit.org.webrtc.FrameTransformerChain
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpSender

/**
 * Creates the frame transformers that go on senders and receivers. None of them are installed on
 * creation; [FrameTransformerInstaller] does that.
 *
 * Behind an interface so tests can replace the native objects.
 */
internal interface FrameTransformerFactory {
    fun createFrameMetadataTransformer(direction: FrameMetadataTransformer.Direction): FrameMetadataTransformer

    /** Runs each frame through [first], then [second]. */
    fun createChain(first: FrameTransformer, second: FrameTransformer): FrameTransformerChain

    fun createFrameCryptor(
        sender: RtpSender,
        participantId: String,
        algorithm: FrameCryptorAlgorithm,
        keyProvider: FrameCryptorKeyProvider,
    ): FrameCryptor

    fun createFrameCryptor(
        receiver: RtpReceiver,
        participantId: String,
        algorithm: FrameCryptorAlgorithm,
        keyProvider: FrameCryptorKeyProvider,
    ): FrameCryptor
}

internal class DefaultFrameTransformerFactory(
    private val peerConnectionFactory: PeerConnectionFactory,
) : FrameTransformerFactory {
    override fun createFrameMetadataTransformer(direction: FrameMetadataTransformer.Direction): FrameMetadataTransformer =
        FrameMetadataTransformerFactory.createFrameMetadataTransformer(direction)

    override fun createChain(first: FrameTransformer, second: FrameTransformer): FrameTransformerChain =
        FrameTransformerChain(first, second)

    override fun createFrameCryptor(
        sender: RtpSender,
        participantId: String,
        algorithm: FrameCryptorAlgorithm,
        keyProvider: FrameCryptorKeyProvider,
    ): FrameCryptor = FrameCryptorFactory.createUninstalledFrameCryptor(
        peerConnectionFactory,
        sender,
        participantId,
        algorithm,
        keyProvider,
    )

    override fun createFrameCryptor(
        receiver: RtpReceiver,
        participantId: String,
        algorithm: FrameCryptorAlgorithm,
        keyProvider: FrameCryptorKeyProvider,
    ): FrameCryptor = FrameCryptorFactory.createUninstalledFrameCryptor(
        peerConnectionFactory,
        receiver,
        participantId,
        algorithm,
        keyProvider,
    )
}
