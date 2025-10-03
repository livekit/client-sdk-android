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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
// JWTPayload requires Android Base64 implementation, so robolectric runner needed.
@RunWith(RobolectricTestRunner::class)
class TokenSourceTest : BaseTest() {

    @Test
    fun testLiteral() = runTest {
        val source = TokenSource.fromLiteral("https://www.example.com", "token")

        val response = source.fetch()

        assertEquals("https://www.example.com", response.serverUrl)
        assertEquals("token", response.participantToken)
    }

    @Test
    fun testCustom() = runTest {
        var wasCalled = false
        val requestOptions = TokenRequestOptions(
            roomName = "room_name",
        )
        val source = TokenSource.fromCustom { options ->
            wasCalled = true
            assertEquals(requestOptions, options)
            return@fromCustom TokenSourceResponse("https://www.example.com", "token")
        }

        val response = source.fetch(requestOptions)

        assertTrue(wasCalled)
        assertEquals("https://www.example.com", response.serverUrl)
        assertEquals("token", response.participantToken)
    }

    @Test
    fun testEndpoint() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{
    "serverUrl": "wss://www.example.com",
    "roomName": "room-name",
    "participantName": "participant-name",
    "participantToken": "token"
}""",
            ),
        )

        val source = TokenSource.fromEndpoint(
            server.url("/").toUrl(),
            method = "POST",
            headers = mapOf("hello" to "world"),
        )
        val options = TokenRequestOptions(
            roomName = "room-name",
            participantName = "participant-name",
            participantIdentity = "participant-identity",
            participantMetadata = "participant-metadata",
            roomConfiguration = RoomConfiguration(
                name = "room-name",
                emptyTimeout = 10,
                departureTimeout = 10,
                maxParticipants = 100,
                metadata = "room-metadata",
                minPlayoutDelay = 1,
                maxPlayoutDelay = 1,
                syncStreams = 1,
                agents = RoomAgentDispatch(
                    agentName = "agent-name",
                    metadata = "agent-metadata",
                ),
            ),
        )

        val response = source.fetch(options)
        assertEquals("wss://www.example.com", response.serverUrl)
        assertEquals("token", response.participantToken)

        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("world", request.headers["hello"])

        val requestBody = request.body.readUtf8()

        println(requestBody)

        val json = Json.parseToJsonElement(requestBody).jsonObject.toMap()

        // Check sends snake_case
        assertEquals("room-name", json["room_name"]?.jsonPrimitive?.content)
    }


    @Ignore("For manual testing only.")
    @Test
    fun testTokenServer() = runTest {
        val source = TokenSource.fromSandboxTokenServer(
            "", // Put sandboxId here to test manually.
        )
        val options = TokenRequestOptions(
            roomName = "room-name",
            participantName = "participant-name",
            participantIdentity = "participant-identity",
            participantMetadata = "participant-metadata",
            roomConfiguration = RoomConfiguration(
                name = "room-name",
                emptyTimeout = 10,
                departureTimeout = 10,
                maxParticipants = 100,
                metadata = "room-metadata",
                minPlayoutDelay = 1,
                maxPlayoutDelay = 1,
                syncStreams = 1,
                agents = RoomAgentDispatch(
                    agentName = "agent-name",
                    metadata = "agent-metadata",
                ),
            ),
        )

        val response = source.fetch(options)
        println(response)

        assertTrue(response.hasValidToken())
    }

}
