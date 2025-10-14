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

import io.livekit.android.test.BaseTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
// JWTPayload requires Android Base64 implementation, so robolectric runner needed.
@RunWith(RobolectricTestRunner::class)
class CachingTokenSourceTest : BaseTest() {

    @Test
    fun tokenIsValid() {
        val tokenResponse = TokenSourceResponse(
            "wss://www.example.com",
            TokenPayloadTest.TEST_TOKEN,
        )

        assertTrue(tokenResponse.hasValidToken(date = Date(5000000000000)))
    }

    @Test
    fun tokenBeforeNbfIsInvalid() {
        val tokenResponse = TokenSourceResponse(
            "wss://www.example.com",
            TokenPayloadTest.TEST_TOKEN,
        )

        assertTrue(tokenResponse.hasValidToken(date = Date(0)))
    }

    @Test
    fun tokenAfterExpIsInvalid() {
        val tokenResponse = TokenSourceResponse(
            "wss://www.example.com",
            TokenPayloadTest.TEST_TOKEN,
        )

        assertTrue(tokenResponse.hasValidToken(date = Date(9999999990000)))
    }

    @Test
    fun cachedValidTokenOnlyFetchedOnce() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{
    "serverUrl": "wss://www.example.com",
    "roomName": "room-name",
    "participantName": "participant-name",
    "participantToken": "${TokenPayloadTest.TEST_TOKEN}"
}""",
            ),
        )

        val source = TokenSource
            .fromEndpoint(server.url("/").toUrl())
            .cached()

        val firstResponse = source.fetch()
        val secondResponse = source.fetch()

        assertEquals(firstResponse, secondResponse)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cachedInvalidTokenRefetched() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{
    "serverUrl": "wss://www.example.com",
    "roomName": "room-name",
    "participantName": "participant-name",
    "participantToken": "${EXPIRED_TOKEN}"
}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{
    "serverUrl": "wss://www.example.com",
    "roomName": "room-name",
    "participantName": "participant-name",
    "participantToken": "${TokenPayloadTest.TEST_TOKEN}"
}""",
            ),
        )

        val source = TokenSource
            .fromEndpoint(server.url("/").toUrl())
            .cached()

        val firstResponse = source.fetch()
        val secondResponse = source.fetch()

        assertNotEquals(firstResponse, secondResponse)
        assertEquals(2, server.requestCount)
    }

    companion object {
        // Token with an nbf and exp of 0 seconds.
        const val EXPIRED_TOKEN = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IjlhMzJiZTg2NzkyZTM3Nm" +
            "I3ZTBlMmIyNjVjMjY1YTA5In0.eyJpYXQiOjAsIm5iZiI6MCwiZXhwIjowfQ.8oV9K-CeULScAjFIK2O7sxEGUD7" +
            "su3kCQv3Q8rhk0Hg_AuzQixJfz2Pt0rJUwLWhF0mSlcYMUKdR0yp12RfrdA"
    }
}
