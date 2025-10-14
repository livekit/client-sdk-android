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
 * Decodes a LiveKit connection token and grabs relevant information from it.
 *
 * https://docs.livekit.io/home/get-started/authentication/
 */
data class TokenPayload(val token: String) {

    val jwt = JWT(token)
    val issuer: String?
        get() = jwt.issuer

    val subject: String?
        get() = jwt.subject

    /**
     * Date specifying the time
     * [after which this token is invalid](https://tools.ietf.org/html/draft-ietf-oauth-json-web-token-25#section-4.1.4).
     */
    val expiresAt: Date?
        get() = jwt.expiresAt

    /**
     * Date specifying the time
     * [before which this token is invalid](https://tools.ietf.org/html/draft-ietf-oauth-json-web-token-25#section-4.1.5).
     */
    val notBefore: Date?
        get() = jwt.notBefore

    val issuedAt: Date?
        get() = jwt.issuedAt

    // Claims are parsed through GSON each time and potentially costly.
    // Cache them with lazy delegates.

    /** Display name for the participant, equivalent to [io.livekit.android.room.participant.Participant.name] */
    val name: String?
        by lazy(LazyThreadSafetyMode.NONE) { jwt.claims["name"]?.asString() }

    /** Unique identity of the user, equivalent to [io.livekit.android.room.participant.Participant.identity] */
    val identity: String?
        by lazy(LazyThreadSafetyMode.NONE) { subject ?: jwt.claims["identity"]?.asString() }

    /** The metadata of the participant */
    val metadata: String?
        by lazy(LazyThreadSafetyMode.NONE) { jwt.claims["metadata"]?.asString() }

    /** Key/value attributes attached to the participant */
    @Suppress("UNCHECKED_CAST")
    val attributes: Map<String, String>?
        by lazy(LazyThreadSafetyMode.NONE) { jwt.claims["attributes"]?.asObject(Map::class.java) as? Map<String, String> }

    /**
     * Room related permissions.
     */
    val video: VideoGrants?
        by lazy(LazyThreadSafetyMode.NONE) { jwt.claims["video"]?.asObject(VideoGrants::class.java) }
}

data class VideoGrants(
    /**
     * The name of the room.
     */
    val room: String?,
    /**
     * Permission to join a room
     */
    val roomJoin: Boolean?,
    val canPublish: Boolean?,
    val canPublishData: Boolean?,
    /**
     * The list of sources this participant can publish from.
     */
    val canPublishSources: List<String>?,
    val canSubscribe: Boolean?,
)
