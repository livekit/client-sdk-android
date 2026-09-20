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

package io.livekit.android.e2ee

import io.livekit.android.test.BaseTest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.livekit_datatrack.DecryptionException
import uniffi.livekit_datatrack.EncryptedPayload
import uniffi.livekit_datatrack.EncryptionException

class DataTrackCryptorTest : BaseTest() {

    @Test
    fun encryptThrowsWhenThereIsNoE2eeManager() {
        val cryptor = DataTrackCryptor { null }
        try {
            cryptor.encrypt(byteArrayOf(1, 2, 3))
            fail("expected EncryptionException.Failed")
        } catch (e: EncryptionException.Failed) {
            assertTrue(e.message!!.contains("E2EE manager"))
        }
    }

    @Test
    fun decryptThrowsWhenThereIsNoE2eeManager() {
        val cryptor = DataTrackCryptor { null }
        try {
            cryptor.decrypt(
                EncryptedPayload(
                    payload = byteArrayOf(1),
                    iv = byteArrayOf(2),
                    keyIndex = 0u,
                ),
                "sender",
            )
            fail("expected DecryptionException.Failed")
        } catch (e: DecryptionException.Failed) {
            assertTrue(e.message!!.contains("E2EE manager"))
        }
    }
}
