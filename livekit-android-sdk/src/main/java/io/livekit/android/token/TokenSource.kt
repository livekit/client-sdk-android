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

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import java.net.URL

/**
 * The options for a token request.
 *
 * When making a custom request against a token server, this is converted into
 * [TokenSourceRequest] through [toRequest].
 */
data class TokenRequestOptions(
    val roomName: String? = null,
    val participantName: String? = null,
    val participantIdentity: String? = null,
    val participantMetadata: String? = null,
    val participantAttributes: Map<String, String>? = null,
    val agentName: String? = null,
    val agentMetadata: String? = null,
)

/**
 * Converts a [TokenRequestOptions] to [TokenSourceRequest], a JSON serializable request body.
 */
fun TokenRequestOptions.toRequest(): TokenSourceRequest {
    val agents = if (agentName != null || agentMetadata != null) {
        listOf(
            RoomAgentDispatch(
                agentName = agentName,
                metadata = agentMetadata,
            ),
        )
    } else {
        null
    }
    return TokenSourceRequest(
        roomName = roomName,
        participantName = participantName,
        participantIdentity = participantIdentity,
        participantMetadata = participantMetadata,
        participantAttributes = participantAttributes,
        roomConfig = RoomConfiguration(
            agents = agents,
        ),
    )
}

/**
 * The JSON serializable format of the request sent to standard LiveKit token servers.
 *
 * Equivalent to [livekit.LivekitTokenSource.TokenSourceRequest]
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TokenSourceRequest(
    val roomName: String?,
    val participantName: String?,
    val participantIdentity: String?,
    val participantMetadata: String?,
    val participantAttributes: Map<String, String>?,
    val roomConfig: RoomConfiguration?,
)

/**
 * @see livekit.LivekitRoom.RoomConfiguration
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RoomConfiguration(
    val name: String? = null,
    val emptyTimeout: Int? = null,
    val departureTimeout: Int? = null,
    val maxParticipants: Int? = null,
    val metadata: String? = null,
    // egress is omitted due to complexity of serialization here.
    val minPlayoutDelay: Int? = null,
    val maxPlayoutDelay: Int? = null,
    val syncStreams: Int? = null,
    val agents: List<RoomAgentDispatch>? = null,
)

/**
 * @see livekit.LivekitAgentDispatch.RoomAgentDispatch
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RoomAgentDispatch(
    val agentName: String? = null,
    val metadata: String? = null,
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TokenSourceResponse(
    /**
     * The server url to connect with the associated [participantToken].
     */
    val serverUrl: String,
    /**
     * The JWT token used to connect to the room.
     *
     * Specific details of the payload may be examined with [TokenPayload]
     * (such as the permissions, metadata, etc.)
     */
    val participantToken: String,
    /**
     * The room name.
     *
     * Note: Not required to be sent by the server response.
     */
    val roomName: String? = null,
    /**
     * The participant name.
     *
     * Note: Not required to be sent by the server response.
     */
    val participantName: String? = null,
)

interface TokenSource {
    companion object {
        /**
         * Creates a [FixedTokenSource] that immediately returns with the supplied [serverUrl] and [participantToken].
         *
         * @see cached
         * @see CachingFixedTokenSource
         */
        fun fromLiteral(serverUrl: String, participantToken: String): FixedTokenSource = LiteralTokenSource(serverUrl, participantToken)

        /**
         * Creates a custom [ConfigurableTokenSource] that executes [block] to fetch the credentials.
         *
         * @see cached
         * @see CachingConfigurableTokenSource
         */
        fun fromCustom(block: suspend (options: TokenRequestOptions) -> TokenSourceResponse): ConfigurableTokenSource = CustomTokenSource(block)

        /**
         * Creates a [ConfigurableTokenSource] that fetches from a given [url] using the standard token server format.
         *
         * @see cached
         * @see CachingConfigurableTokenSource
         */
        fun fromEndpoint(url: URL, method: String = "POST", headers: Map<String, String> = emptyMap()): ConfigurableTokenSource = EndpointTokenSourceImpl(
            url = url,
            method = method,
            headers = headers,
        )

        /**
         * Creates a [ConfigurableTokenSource] that fetches from a sandbox token server for credentials,
         * which supports quick prototyping/getting started types of use cases.
         *
         * Note: This token provider is **insecure** and should **not** be used in production.
         *
         * @see cached
         * @see CachingConfigurableTokenSource
         */
        fun fromSandboxTokenServer(sandboxId: String, options: SandboxTokenServerOptions = SandboxTokenServerOptions()): ConfigurableTokenSource = SandboxTokenSource(
            sandboxId = sandboxId,
            options = options,
        )
    }
}

/**
 * A non-configurable token source that does not take any options.
 */
interface FixedTokenSource : TokenSource {
    suspend fun fetch(): TokenSourceResponse
}

/**
 * A configurable token source takes in a [TokenRequestOptions] when requesting credentials.
 */
interface ConfigurableTokenSource : TokenSource {
    suspend fun fetch(options: TokenRequestOptions = TokenRequestOptions()): TokenSourceResponse
}
