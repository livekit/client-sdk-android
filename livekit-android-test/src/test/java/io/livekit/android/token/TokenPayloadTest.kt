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
import junit.framework.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

// JWTPayload requires Android Base64 implementation, so robolectric runner needed.
@RunWith(RobolectricTestRunner::class)
class TokenPayloadTest {
    companion object {
        // Test JWT created for test purposes only.
        // Does not actually auth against anything.
        // Nbf date set at 1234567890 seconds (Fri Feb 13 2009 23:31:30 GMT+0000)
        // Exp date set at 9876543210 seconds (Fri Dec 22 2282 20:13:30 GMT+0000)
        const val TEST_TOKEN = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6ImRiY2UzNm" +
            "JkNjBjZDI5NWM2ODExNTBiMGU2OGFjNGU5In0.eyJzdWIiOiIxMjM0NTY3ODkwIiwiZXhwIjo" +
            "5ODc2NTQzMjEwLCJuYmYiOjEyMzQ1Njc4OTAsImlhdCI6MTIzNDU2Nzg5MH0.sYQ-blJC16BL" +
            "ltZduvvkOqoa7PBBbYQh2p50ofRfVjZw6XIPgMo-oXXBI49J4IOsOKjzK_VeHlchxUitdIPtkg"

        // Test JWT created for test purposes only.
        // Does not actually auth against anything.
        // Filled with various dummy data.
        const val FULL_TEST_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJh" +
            "YmNkZWZnIiwiZXhwIjozMzI1NDI4MjgwNCwic3ViIjoiaWRlbnRpdHkiLCJuYW1lIjoibmFtZ" +
            "SIsIm1ldGFkYXRhIjoibWV0YWRhdGEiLCJzaGEyNTYiOiJnZmVkY2JhIiwicm9vbVByZXNldC" +
            "I6InJvb21QcmVzZXQiLCJhdHRyaWJ1dGVzIjp7ImtleSI6InZhbHVlIn0sInJvb21Db25maWc" +
            "iOnsibmFtZSI6Im5hbWUiLCJlbXB0eV90aW1lb3V0IjoxLCJkZXBhcnR1cmVfdGltZW91dCI6" +
            "MiwibWF4X3BhcnRpY2lwYW50cyI6MywiZWdyZXNzIjp7InJvb20iOnsicm9vbV9uYW1lIjoib" +
            "mFtZSJ9fSwibWluX3BsYXlvdXRfZGVsYXkiOjQsIm1heF9wbGF5b3V0X2RlbGF5Ijo1LCJzeW" +
            "5jX3N0cmVhbXMiOnRydWV9LCJ2aWRlbyI6eyJyb29tIjoicm9vbV9uYW1lIiwicm9vbUpvaW4" +
            "iOnRydWUsImNhblB1Ymxpc2giOnRydWUsImNhblB1Ymxpc2hTb3VyY2VzIjpbImNhbWVyYSIs" +
            "Im1pY3JvcGhvbmUiXX0sInNpcCI6eyJhZG1pbiI6dHJ1ZX19.kFgctvUje5JUxwPCNSvFri-g" +
            "0b0AEG6hiZS-xQ3SAI4"
    }

    @Test
    fun decode() {
        val payload = TokenPayload(TEST_TOKEN)

        assertEquals(Date(1234567890000), payload.notBefore)
        assertEquals(Date(9876543210000), payload.expiresAt)
    }

    @Test
    fun fullTestDecode() {
        val payload = TokenPayload(FULL_TEST_TOKEN)

        assertEquals("identity", payload.subject)
        assertEquals("identity", payload.identity)
        assertEquals("name", payload.name)
        assertEquals("metadata", payload.metadata)
        assertEquals("value", payload.attributes?.get("key"))

        val videoGrants = payload.video
        assertEquals("room_name", videoGrants?.room)
        assertEquals(listOf("camera", "microphone"), videoGrants?.canPublishSources)
        assertEquals(true, videoGrants?.roomJoin)
        assertEquals(true, videoGrants?.canPublish)
        assertNull(videoGrants?.canPublishData)
        assertNull(videoGrants?.canSubscribe)
    }
}
