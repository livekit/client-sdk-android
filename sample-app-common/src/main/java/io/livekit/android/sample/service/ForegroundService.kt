/*
 * Copyright 2024 LiveKit, Inc.
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

package io.livekit.android.sample.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

/**
 * A foreground service will keep the app alive in the background.
 *
 * Beginning with Android 14, foreground service types are required.
 * This service declares the mediaPlayback, camera, and microphone types
 * in the AndroidManifest.xml.
 *
 * This ensures that the app will continue to be able to playback media and
 * access the camera/microphone in the background. Apps that don't declare
 * these will run into issues when trying to access them from the background.
 */

open class ForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }

        val actualNotification =
            NotificationCompat.Builder(this, DEFAULT_CHANNEL_ID)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

        startForeground(DEFAULT_NOTIFICATION_ID, actualNotification)
        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            DEFAULT_CHANNEL_ID,
            "Foreground",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // not used.
    }

    companion object {
        const val DEFAULT_NOTIFICATION_ID = 3456
        const val DEFAULT_CHANNEL_ID = "livekit_example_foreground"
    }
}
