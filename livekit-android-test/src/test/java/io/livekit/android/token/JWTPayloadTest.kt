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

package io.livekit.android.token

import junit.framework.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

// JWTPayload requires Android Base64 implementation, so robolectric runner needed.
@RunWith(RobolectricTestRunner::class)
class JWTPayloadTest {
    companion object {
        const val TEST_TOKEN = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6ImRiY2UzNm" +
            "JkNjBjZDI5NWM2ODExNTBiMGU2OGFjNGU5In0.eyJzdWIiOiIxMjM0NTY3ODkwIiwiZXhwIjo" +
            "5ODc2NTQzMjEwLCJuYmYiOjEyMzQ1Njc4OTAsImlhdCI6MTIzNDU2Nzg5MH0.sYQ-blJC16BL" +
            "ltZduvvkOqoa7PBBbYQh2p50ofRfVjZw6XIPgMo-oXXBI49J4IOsOKjzK_VeHlchxUitdIPtkg"
    }

    @Test
    fun decode() {
        val payload = JWTPayload(TEST_TOKEN)

        assertEquals(Date(1234567890000), payload.notBefore)
        assertEquals(Date(9876543210000), payload.expiresAt)
    }
}
