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

package io.livekit.android.room.datatrack

import kotlinx.serialization.Serializable

/**
 * A server-assigned data track identifier.
 *
 * SIDs are not stable across a publisher's full reconnect: the track object survives and its SID
 * is rewritten in place. Prefer [RemoteDataTrack.name] when keying a map of remote tracks.
 */
@Serializable
@JvmInline
value class DataTrackSid(val value: String) {
    override fun toString(): String = value
}
