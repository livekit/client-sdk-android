package io.livekit.android.stats

import android.os.Build
import io.livekit.android.BuildConfig
import io.livekit.android.room.SignalClient
import livekit.LivekitModels

internal fun getClientInfo() = with(LivekitModels.ClientInfo.newBuilder()) {
    sdk = LivekitModels.ClientInfo.SDK.ANDROID
    protocol = SignalClient.PROTOCOL_VERSION
    version = BuildConfig.VERSION_NAME
    os = "android"
    osVersion = Build.VERSION.RELEASE ?: ""

    val vendor = Build.MANUFACTURER ?: ""
    val model = Build.MODEL ?: ""
    deviceModel = ("$vendor $model").trim()
    build()
}
