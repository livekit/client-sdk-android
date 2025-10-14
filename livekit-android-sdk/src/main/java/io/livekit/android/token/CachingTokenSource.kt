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

import io.livekit.android.util.LKLog
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class BaseCachingTokenSource(
    private val store: TokenStore,
    private val validator: TokenValidator,
) {

    /**
     * The entrypoint for the caching store; subclasses should call this from their fetch methods.
     *
     * If a new token is needed, [fetchFromSource] will be called.
     */
    internal suspend fun fetchImpl(options: TokenRequestOptions?): TokenSourceResponse {
        val cached = store.retrieve()

        if (cached != null && cached.options == options && validator.invoke(cached.options, cached.response)) {
            return cached.response
        }

        val response = fetchFromSource(options)
        store.store(options, response)
        return response
    }

    /**
     * Implement this to fetch the [TokenSourceResponse] from the token source.
     */
    abstract suspend fun fetchFromSource(options: TokenRequestOptions?): TokenSourceResponse

    /**
     * Invalidate the cached credentials, forcing a fresh fetch on the next request.
     */
    suspend fun invalidate() {
        store.clear()
    }

    /**
     * Get the cached credentials if one exists.
     */
    suspend fun cachedResponse(): TokenSourceResponse? {
        return store.retrieve()?.response
    }
}

class CachingFixedTokenSource(
    private val source: FixedTokenSource,
    store: TokenStore,
    validator: TokenValidator,
) : BaseCachingTokenSource(store, validator), FixedTokenSource {
    override suspend fun fetchFromSource(options: TokenRequestOptions?): TokenSourceResponse {
        return source.fetch()
    }

    override suspend fun fetch(): TokenSourceResponse {
        return fetchImpl(null)
    }
}

class CachingConfigurableTokenSource(
    private val source: ConfigurableTokenSource,
    store: TokenStore,
    validator: TokenValidator,
) : BaseCachingTokenSource(store, validator), ConfigurableTokenSource {
    override suspend fun fetchFromSource(options: TokenRequestOptions?): TokenSourceResponse {
        return source.fetch(options ?: TokenRequestOptions())
    }

    override suspend fun fetch(options: TokenRequestOptions): TokenSourceResponse {
        return fetchImpl(options)
    }
}

/**
 * Wraps the token store with a cache so that it reuses the token as long as it is valid.
 */
fun FixedTokenSource.cached(
    store: TokenStore = InMemoryTokenStore(),
    validator: TokenValidator = defaultValidator,
) = CachingFixedTokenSource(this, store, validator)

/**
 * Wraps the token store with a cache so that it reuses the token as long as it is valid.
 *
 * If the request options passed to [ConfigurableTokenSource.fetch] change, a new token
 * will be fetched.
 */
fun ConfigurableTokenSource.cached(
    store: TokenStore = InMemoryTokenStore(),
    validator: TokenValidator = defaultValidator,
) = CachingConfigurableTokenSource(this, store, validator)

typealias TokenValidator = (options: TokenRequestOptions?, response: TokenSourceResponse) -> Boolean

interface TokenStore {
    suspend fun retrieve(): Item?
    suspend fun store(options: TokenRequestOptions?, response: TokenSourceResponse)
    suspend fun clear()

    data class Item(
        val options: TokenRequestOptions?,
        val response: TokenSourceResponse,
    )
}

internal class InMemoryTokenStore() : TokenStore {
    var item: TokenStore.Item? = null
    override suspend fun retrieve(): TokenStore.Item? = item

    override suspend fun store(options: TokenRequestOptions?, response: TokenSourceResponse) {
        item = TokenStore.Item(options, response)
    }

    override suspend fun clear() {
        item = null
    }
}

private val defaultValidator: TokenValidator = { options, response ->
    response.hasValidToken()
}

/**
 * Validates whether the JWT token is still valid.
 */
fun TokenSourceResponse.hasValidToken(tolerance: Duration = 60.seconds, date: Date = Date()): Boolean {
    try {
        val jwt = TokenPayload(participantToken)
        val now = Date()
        val expiresAt = jwt.expiresAt
        val nbf = jwt.notBefore

        val isBefore = nbf != null && now.before(nbf)
        val hasExpired = expiresAt != null && now.after(Date(expiresAt.time + tolerance.inWholeMilliseconds))

        return !isBefore && !hasExpired
    } catch (e: Exception) {
        LKLog.i(e) { "Could not validate existing token" }
        return false
    }
}
