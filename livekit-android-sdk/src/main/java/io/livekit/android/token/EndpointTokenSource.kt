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

import io.livekit.android.dagger.globalOkHttpClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class EndpointTokenSourceImpl(
    override val url: URL,
    override val method: String,
    override val headers: Map<String, String>,
) : EndpointTokenSource

data class SandboxTokenServerOptions(
    val baseUrl: String? = null,
)

internal class SandboxTokenSource(sandboxId: String, options: SandboxTokenServerOptions) : EndpointTokenSource {
    override val url: URL = URL("${options.baseUrl ?: "https://cloud-api.livekit.io"}/api/v2/sandbox/connection-details")
    override val headers: Map<String, String> = mapOf(
        "X-Sandbox-ID" to sandboxId,
    )
}

internal interface EndpointTokenSource : ConfigurableTokenSource {
    /** The url to fetch the token from */
    val url: URL

    /** The HTTP method to use (defaults to "POST") */
    val method: String
        get() = "POST"

    /** Additional HTTP headers to include with the request */
    val headers: Map<String, String>

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun fetch(options: TokenRequestOptions): TokenSourceResponse = suspendCancellableCoroutine { continuation ->
        try {
            val okHttpClient = globalOkHttpClient

            val snakeCaseJson = Json {
                namingStrategy = JsonNamingStrategy.SnakeCase
                ignoreUnknownKeys = true
                explicitNulls = false
            }

            // v1 token server returns camelCase keys
            val camelCaseJson = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
            val body = snakeCaseJson.encodeToString(options.toRequest())

            val request = Request.Builder()
                .url(url)
                .method(method, body.toRequestBody())
                .addHeader("Content-Type", "application/json")
                .apply {
                    headers.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .build()

            val call = okHttpClient.newCall(request)
            call.enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        val bodyStr = response.body?.string()
                        if (bodyStr == null) {
                            continuation.resumeWithException(NullPointerException("No response returned from server"))
                            return
                        }

                        var tokenResponse: TokenSourceResponse? = null
                        try {
                            tokenResponse = snakeCaseJson.decodeFromString<TokenSourceResponse>(bodyStr)
                        } catch (e: Exception) {
                        }

                        if (tokenResponse == null) {
                            // snake_case decoding failed, try camelCase decoding for v1 back compatibility
                            try {
                                tokenResponse = camelCaseJson.decodeFromString<TokenSourceResponse>(bodyStr)
                            } catch (e: Exception) {
                                continuation.resumeWithException(IllegalArgumentException("Failed to decode response from token server", e))
                                return
                            }
                        }

                        continuation.resume(tokenResponse)
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }
                },
            )
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}
