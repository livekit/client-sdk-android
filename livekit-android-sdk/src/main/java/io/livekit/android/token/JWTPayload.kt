/*
 * Copyright 2024-2025 LiveKit, Inc.
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

import com.auth0.android.jwt.JWT
import java.util.Date

/**
 * Decodes a JWT and grabs relevant information from it.
 *
 * https://docs.livekit.io/home/get-started/authentication/
 *
 * @suppress
 */
class JWTPayload(token: String) {

    val expiresAt: Date?

    /**
     * Date specifying the time
     * [before which this token is invalid](https://tools.ietf.org/html/draft-ietf-oauth-json-web-token-25#section-4.1.5)
     * .
     */
    val notBefore: Date?

    init {
        val jwt = JWT(token)
        expiresAt = jwt.expiresAt
        notBefore = jwt.notBefore
    }
}
