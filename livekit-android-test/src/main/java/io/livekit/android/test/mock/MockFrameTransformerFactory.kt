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

package io.livekit.android.test.mock

import io.livekit.android.room.framemetadata.FrameTransformerFactory
import livekit.org.webrtc.FrameCryptor
import livekit.org.webrtc.FrameCryptorAlgorithm
import livekit.org.webrtc.FrameCryptorKeyProvider
import livekit.org.webrtc.FrameMetadata
import livekit.org.webrtc.FrameMetadataTransformer
import livekit.org.webrtc.FrameTransformer
import livekit.org.webrtc.FrameTransformerChain
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpSender
import org.mockito.Mockito
import org.mockito.kotlin.whenever

/**
 * Records the frame transformers the SDK creates, as Mockito mocks in place of the native objects.
 */
class MockFrameTransformerFactory {

    data class CreatedChain(
        val chain: FrameTransformerChain,
        val first: FrameTransformer,
        val second: FrameTransformer,
    )

    val metadataTransformers = mutableListOf<FrameMetadataTransformer>()
    val chains = mutableListOf<CreatedChain>()
    val senderCryptors = mutableListOf<FrameCryptor>()
    val receiverCryptors = mutableListOf<FrameCryptor>()

    /** What a receive transformer's `lookup` returns. */
    var lookupResult: (rtpTimestamp: Long) -> FrameMetadata? = { null }

    internal val factory: FrameTransformerFactory = object : FrameTransformerFactory {
        override fun createFrameMetadataTransformer(direction: FrameMetadataTransformer.Direction): FrameMetadataTransformer {
            val transformer = Mockito.mock(FrameMetadataTransformer::class.java)
            whenever(transformer.direction).thenReturn(direction)
            whenever(transformer.lookup(Mockito.anyLong())).thenAnswer { lookupResult(it.getArgument(0)) }
            metadataTransformers.add(transformer)
            return transformer
        }

        override fun createChain(first: FrameTransformer, second: FrameTransformer): FrameTransformerChain {
            val chain = Mockito.mock(FrameTransformerChain::class.java)
            chains.add(CreatedChain(chain, first, second))
            return chain
        }

        override fun createFrameCryptor(
            sender: RtpSender,
            participantId: String,
            algorithm: FrameCryptorAlgorithm,
            keyProvider: FrameCryptorKeyProvider,
        ): FrameCryptor = Mockito.mock(FrameCryptor::class.java).also { senderCryptors.add(it) }

        override fun createFrameCryptor(
            receiver: RtpReceiver,
            participantId: String,
            algorithm: FrameCryptorAlgorithm,
            keyProvider: FrameCryptorKeyProvider,
        ): FrameCryptor = Mockito.mock(FrameCryptor::class.java).also { receiverCryptors.add(it) }
    }
}
