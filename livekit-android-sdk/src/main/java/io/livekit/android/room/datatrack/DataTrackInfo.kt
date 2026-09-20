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

import io.livekit.uniffi.DataTrackInfo as FfiDataTrackInfo

/**
 * Metadata describing a published data track.
 *
 * @param sid Server-assigned unique identifier for the track. Not stable across a publisher's
 * full reconnect; see [DataTrackSid].
 * @param name Name chosen by the publisher; unique per participant.
 * @param usesE2ee Whether the track's frames are end-to-end encrypted.
 * @param schema Schema describing the track's frames, if the publisher declared one.
 * @param frameEncoding Encoding of the track's frames, if the publisher declared one.
 */
data class DataTrackInfo(
    val sid: DataTrackSid,
    val name: String,
    val usesE2ee: Boolean,
    val schema: DataTrackSchemaId?,
    val frameEncoding: DataTrackFrameEncoding?,
) {
    internal constructor(ffi: FfiDataTrackInfo) : this(
        sid = DataTrackSid(ffi.sid),
        name = ffi.name,
        usesE2ee = ffi.usesE2ee,
        schema = ffi.schema?.let { DataTrackSchemaId(it) },
        frameEncoding = ffi.frameEncoding?.let { DataTrackFrameEncoding.fromFfi(it) },
    )
}
