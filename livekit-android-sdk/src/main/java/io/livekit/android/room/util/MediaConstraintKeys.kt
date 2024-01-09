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

package io.livekit.android.room.util

import livekit.org.webrtc.MediaConstraints

object MediaConstraintKeys {
    const val OFFER_TO_RECV_AUDIO = "OfferToReceiveAudio"
    const val OFFER_TO_RECV_VIDEO = "OfferToReceiveVideo"
    const val ICE_RESTART = "IceRestart"

    const val FALSE = "false"
    const val TRUE = "true"
}

fun MediaConstraints.findConstraint(key: String): String? {
    return mandatory.firstOrNull { it.key == key }?.value
        ?: optional.firstOrNull { it.key == key }?.value
}
