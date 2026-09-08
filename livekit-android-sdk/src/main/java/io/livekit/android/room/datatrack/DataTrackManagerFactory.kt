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

import io.livekit.uniffi.LocalDataTrackManagerDelegate
import io.livekit.uniffi.LocalDataTrackManagerInterface
import io.livekit.uniffi.RemoteDataTrackManagerDelegate
import io.livekit.uniffi.RemoteDataTrackManagerInterface
import uniffi.livekit_datatrack.DecryptionProvider
import uniffi.livekit_datatrack.EncryptionProvider

/**
 * Creates UniFFI [io.livekit.uniffi.LocalDataTrackManager] instances.
 *
 * @suppress
 */
fun interface LocalDataTrackManagerFactory {
    fun create(
        delegate: LocalDataTrackManagerDelegate,
        encryptionProvider: EncryptionProvider?,
    ): LocalDataTrackManagerInterface
}

/**
 * Creates UniFFI [io.livekit.uniffi.RemoteDataTrackManager] instances.
 *
 * @suppress
 */
fun interface RemoteDataTrackManagerFactory {
    fun create(
        delegate: RemoteDataTrackManagerDelegate,
        decryptionProvider: DecryptionProvider?,
    ): RemoteDataTrackManagerInterface
}
