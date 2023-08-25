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

package io.livekit.android.stats

import android.os.Build
import io.livekit.android.BuildConfig
import io.livekit.android.room.SignalClient
import livekit.LivekitModels

internal fun getClientInfo() = with(LivekitModels.ClientInfo.newBuilder()) {
    sdk = LivekitModels.ClientInfo.SDK.ANDROID
    version = BuildConfig.VERSION_NAME
    os = SignalClient.SDK_TYPE
    osVersion = Build.VERSION.RELEASE ?: ""

    val vendor = Build.MANUFACTURER ?: ""
    val model = Build.MODEL ?: ""
    deviceModel = ("$vendor $model").trim()
    build()
}
