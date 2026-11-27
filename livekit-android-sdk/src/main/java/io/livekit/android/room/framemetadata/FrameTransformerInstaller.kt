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
import livekit.org.webrtc.FrameMetadataTransformer
import livekit.org.webrtc.FrameTransformer
import livekit.org.webrtc.FrameTransformerChain
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpSender
import javax.inject.Inject

/**
 * Installs a track's transformers on its sender or receiver in a single install.
 *
 * With both a cryptor and a metadata transformer, it chains them in the order for the direction:
 * a sender encrypts and then adds the trailer, so the trailer stays readable; a receiver strips the
 * trailer and then decrypts. With only one, it installs that one directly.
 *
 * It holds no state. A chain it creates is returned so the caller can dispose it.
 *
 * @suppress
 */
class FrameTransformerInstaller
@Inject
internal constructor(
    internal val factory: FrameTransformerFactory,
) {
    internal fun installSend(
        sender: RtpSender,
        cryptor: FrameCryptor?,
        metadataTransformer: FrameMetadataTransformer?,
    ): FrameTransformerChain? = install(first = cryptor, second = metadataTransformer) { sender.setFrameTransformer(it) }

    internal fun installReceive(
        receiver: RtpReceiver,
        metadataTransformer: FrameMetadataTransformer?,
        cryptor: FrameCryptor?,
    ): FrameTransformerChain? = install(first = metadataTransformer, second = cryptor) { receiver.setFrameTransformer(it) }

    private inline fun install(
        first: FrameTransformer?,
        second: FrameTransformer?,
        setTransformer: (FrameTransformer) -> Unit,
    ): FrameTransformerChain? {
        if (first != null && second != null) {
            return factory.createChain(first, second).also { setTransformer(it) }
        }
        val single = first ?: second ?: return null
        setTransformer(single)
        return null
    }
}
