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

package io.livekit.android.room.track.screencapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

/**
 * A foreground service is required for screen capture on API level Q (29) and up.
 * This a simple default foreground service to display a notification while screen
 * capturing.
 */
open class ScreenCaptureService : Service() {
    private var binder: IBinder = ScreenCaptureBinder()
    private var bindCount = 0

    override fun onBind(intent: Intent?): IBinder {
        bindCount++
        return binder
    }

    /**
     * @param notificationId id of the notification to be used, or null for [DEFAULT_NOTIFICATION_ID]
     * @param notification notification to be used, or null for a default notification.
     */
    fun start(notificationId: Int?, notification: Notification?) {
        val actualNotification = if (notification != null) {
            notification
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            }
            NotificationCompat.Builder(this, DEFAULT_CHANNEL_ID)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        }

        val actualId = notificationId ?: DEFAULT_NOTIFICATION_ID
        startForeground(actualId, actualNotification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            DEFAULT_CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW,
        )
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(channel)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        bindCount--

        if (bindCount == 0) {
            stopSelf()
        }
        return false
    }

    inner class ScreenCaptureBinder : Binder() {
        val service: ScreenCaptureService
            get() = this@ScreenCaptureService
    }

    companion object {
        const val DEFAULT_NOTIFICATION_ID = 2345
        const val DEFAULT_CHANNEL_ID = "livekit_screen_capture"
    }
}
