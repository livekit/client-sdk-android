/*
 * Copyright 2023 LiveKit, Inc.
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

var defaultRatchetSalt = "LKFrameEncryptionKey"
var defaultMagicBytes = "LK-ROCKS"
var defaultRatchetWindowSize = 0
var defaultFaultTolerance = -1

class E2EEOptions
constructor(
    keyProvider: KeyProvider = BaseKeyProvider(
        defaultRatchetSalt,
        defaultMagicBytes,
        defaultRatchetWindowSize,
        true,
        defaultFaultTolerance,
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
