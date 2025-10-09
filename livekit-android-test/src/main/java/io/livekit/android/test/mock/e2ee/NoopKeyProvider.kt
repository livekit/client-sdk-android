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

package io.livekit.android.test.mock.e2ee

import io.livekit.android.e2ee.KeyProvider
import livekit.org.webrtc.FrameCryptorKeyProvider
import org.mockito.kotlin.mock

class NoopKeyProvider(override val rtcKeyProvider: FrameCryptorKeyProvider = mock<FrameCryptorKeyProvider>(), override var enableSharedKey: Boolean = true) : KeyProvider {
    override fun setSharedKey(key: String, keyIndex: Int?): Boolean {
        return true
    }

    override fun ratchetSharedKey(keyIndex: Int?): ByteArray {
        return ByteArray(0)
    }

    override fun exportSharedKey(keyIndex: Int?): ByteArray {
        return ByteArray(0)
    }

    override fun setKey(key: String, participantId: String?, keyIndex: Int?) {
    }

    override fun ratchetKey(participantId: String, keyIndex: Int?): ByteArray {
        return ByteArray(0)
    }

    override fun exportKey(participantId: String, keyIndex: Int?): ByteArray {
        return ByteArray(0)
    }

    override fun setSifTrailer(trailer: ByteArray) {
    }

    override fun getLatestKeyIndex(participantId: String): Int {
        return 0
    }
}
