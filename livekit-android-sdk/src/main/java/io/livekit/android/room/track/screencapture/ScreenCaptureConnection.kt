/*
 * Copyright 2023-2026 LiveKit, Inc.
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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Handles connecting to a [ScreenCaptureService].
 */
internal class ScreenCaptureConnection(private val context: Context) {
    /**
     * True while [ScreenCaptureService] is connected and [startForeground] can reach it.
     */
    var isBound = false
        private set

    /**
     * True from the start of a bind attempt until its matching unbind attempt. This is a wider
     * window than [isBound]: the binding is owed an unbind even if the service never connects.
     */
    private var isBindRequested = false
    private var service: ScreenCaptureService? = null
    private var hasConnectedCaller = false
    private val queuedConnects = mutableSetOf<CancellableContinuation<Unit>>()
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            LKLog.v { "Screen capture service is disconnected" }
            synchronized(this@ScreenCaptureConnection) {
                isBound = false
                service = null
            }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            LKLog.v { "Screen capture service is connected" }
            val screenCaptureBinder = binder as ScreenCaptureService.ScreenCaptureBinder
            val connects = synchronized(this@ScreenCaptureConnection) {
                if (!isBindRequested) {
                    return
                }
                service = screenCaptureBinder.service
                isBound = true
                queuedConnects.filter { it.isActive }
            }
            connects.forEach { it.resume(Unit) }
        }
    }

    /**
     * Binds to [ScreenCaptureService] and suspends until it is connected.
     *
     * @throws IllegalStateException if the service could not be bound.
     * @throws SecurityException if the caller cannot access the service.
     * @throws CancellationException if [stop] tears the connection down while connecting.
     */
    suspend fun connect() {
        lateinit var continuation: CancellableContinuation<Unit>
        suspendCancellableCoroutine { cont ->
            continuation = cont
            // Binding and enqueueing happen under one lock, so a concurrent stop() either
            // precedes the bind or cancels the waiter, and never strands it against a
            // connection that has already been unbound.
            var outcome: Result<Unit>? = null
            synchronized(this) {
                if (isBound) {
                    outcome = Result.success(Unit)
                } else {
                    queuedConnects.add(cont)
                    val failure = runCatching { if (!isBindRequested) bind() }.exceptionOrNull()
                    if (failure != null) {
                        queuedConnects.remove(cont)
                        outcome = Result.failure(failure)
                    }
                }
            }

            val result = outcome
            if (result == null) {
                // Registered once the waiter is queued, so a continuation that was already
                // cancelled still releases the binding it just requested.
                cont.invokeOnCancellation { abandonConnect(cont) }
            } else {
                // Resumed outside the lock, since resuming runs the continuation inline.
                cont.resumeWith(result)
            }
        }

        val connected = synchronized(this) {
            queuedConnects.remove(continuation)
            if (isBindRequested && isBound) {
                hasConnectedCaller = true
                true
            } else {
                if (queuedConnects.isEmpty() && !hasConnectedCaller) {
                    unbind()
                }
                false
            }
        }
        if (!connected) {
            throw CancellationException("ScreenCaptureService connection was stopped.")
        }
    }

    /**
     * Releases a binding that no longer has anyone waiting on it. Screen share setup is routinely
     * driven from a cancellable scope, and the caller that requested the bind does not necessarily
     * survive to call [stop].
     */
    private fun abandonConnect(cont: CancellableContinuation<Unit>) {
        synchronized(this) {
            queuedConnects.remove(cont)
            if (queuedConnects.isEmpty() && !hasConnectedCaller) {
                unbind()
            }
        }
    }

    private fun bind() {
        val intent = Intent(context, ScreenCaptureService::class.java)
        // A connection is registered even when bindService reports failure, so the unbind is
        // owed from the moment the call is made.
        isBindRequested = true
        val bound = try {
            context.bindService(intent, connection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            unbind()
            throw e
        }
        if (!bound) {
            unbind()
            throw IllegalStateException("Failed to bind ScreenCaptureService.")
        }
    }

    private fun unbind() {
        if (!isBindRequested) {
            return
        }
        isBindRequested = false
        isBound = false
        service = null
        hasConnectedCaller = false
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            LKLog.v(e) { "Screen capture service was not bound" }
        }
    }

    fun startForeground(notificationId: Int? = null, notification: Notification? = null) {
        service?.start(notificationId, notification)
    }

    fun stop() {
        val abandonedConnects = synchronized(this) {
            unbind()
            queuedConnects.toList().also { queuedConnects.clear() }
        }
        // Cancelled outside the lock, since cancellation runs handlers inline.
        abandonedConnects.forEach { it.cancel() }
    }
}
