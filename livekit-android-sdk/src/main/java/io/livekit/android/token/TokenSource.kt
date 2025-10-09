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

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TokenRequestOptions(
    val roomName: String? = null,
    val participantName: String? = null,
    val participantIdentity: String? = null,
    val participantMetadata: String? = null,
    val participantAttributes: Map<String, String>? = null,
    val roomConfig: RoomConfiguration? = null,
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RoomConfiguration(
    val name: String,
    val emptyTimeout: Int,
    val departureTimeout: Int,
    val maxParticipants: Int,
    val metadata: String,
    // egress is omitted due to complexity of serialization here.
    val minPlayoutDelay: Int,
    val maxPlayoutDelay: Int,
    val syncStreams: Int,
    val agents: RoomAgentDispatch,
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RoomAgentDispatch(
    val agentName: String,
    val metadata: String,
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TokenSourceResponse(
    val serverUrl: String,
    val participantToken: String,
    val roomName: String? = null,
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
