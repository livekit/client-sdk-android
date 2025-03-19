package io.livekit.android.room.track.screencapture

import android.app.Notification
import android.content.Intent

class ScreenCaptureParams(
    val mediaProjectionPermissionResultData: Intent,
    val notificationId: Int? = null,
    val notification: Notification? = null,
    val onStop: (() -> Unit)? = null
)
