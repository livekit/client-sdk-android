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

package io.livekit.android.room.track.screencapture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.livekit.android.util.LKLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Handles connecting to a [ScreenCaptureService].
 */
internal class ScreenCaptureConnection(private val context: Context) {
    public var isBound = false
        private set
    private var service: ScreenCaptureService? = null
    private val queuedConnects = mutableSetOf<Continuation<Unit>>()
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            LKLog.v { "Screen capture service is disconnected" }
            isBound = false
            service = null
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            LKLog.v { "Screen capture service is connected" }
            val screenCaptureBinder = binder as ScreenCaptureService.ScreenCaptureBinder
            service = screenCaptureBinder.service
            handleConnect()
        }
    }

    suspend fun connect() {
        if (isBound) {
            return
        }

        val intent = Intent(context, ScreenCaptureService::class.java)
        context.bindService(intent, connection, BIND_AUTO_CREATE)
        return suspendCancellableCoroutine {
            synchronized(this) {
                if (isBound) {
                    it.resume(Unit)
                } else {
                    queuedConnects.add(it)
                }
            }
        }
    }

    fun startForeground(notificationId: Int? = null, notification: Notification? = null) {
        service?.start(notificationId, notification)
    }

    private fun handleConnect() {
        synchronized(this) {
            isBound = true
            queuedConnects.forEach { it.resume(Unit) }
            queuedConnects.clear()
        }
    }

    fun stop() {
        if (isBound) {
            context.unbindService(connection)
        }
        service = null
        isBound = false
    }
}
