/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.sample.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Describes which token source to connect with, in a parcelable form
 * so it can be passed through activity intents.
 */
sealed class TokenSourceArgs : Parcelable {

    @Parcelize
    data class Literal(
        val url: String,
        val token: String,
    ) : TokenSourceArgs()

    @Parcelize
    data class DevTokenServer(
        val tokenServerId: String,
        val roomName: String? = null,
        val participantName: String? = null,
        val participantIdentity: String? = null,
        val agentName: String? = null,
    ) : TokenSourceArgs()
}
