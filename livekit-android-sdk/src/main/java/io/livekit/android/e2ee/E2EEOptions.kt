/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android.e2ee

import livekit.LivekitModels.Encryption

internal const val defaultRatchetSalt = "LKFrameEncryptionKey"
internal const val defaultMagicBytes = "LK-ROCKS"
internal const val defaultRatchetWindowSize = 16
internal const val defaultFailureTolerance = -1
internal const val defaultKeyRingSize = 16
internal const val defaultDiscardFrameWhenCryptorNotReady = false

class E2EEOptions
constructor(
    keyProvider: KeyProvider = BaseKeyProvider(
        defaultRatchetSalt,
        defaultMagicBytes,
        defaultRatchetWindowSize,
        true,
        defaultFailureTolerance,
        defaultKeyRingSize,
        defaultDiscardFrameWhenCryptorNotReady,
    ),
    encryptionType: Encryption.Type = Encryption.Type.GCM,
) {
    var keyProvider: KeyProvider
    var encryptionType: Encryption.Type = Encryption.Type.NONE

    init {
        this.keyProvider = keyProvider
        this.encryptionType = encryptionType
    }
}
