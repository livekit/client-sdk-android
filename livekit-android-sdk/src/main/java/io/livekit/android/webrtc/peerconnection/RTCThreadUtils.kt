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

package io.livekit.android.webrtc.peerconnection

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

// Executor thread is started once and is used for all
// peer connection API calls to ensure new peer connection factory is
// created on the same thread as previously destroyed factory.

private const val EXECUTOR_THREADNAME_PREFIX = "LK_RTC_THREAD"
private val threadFactory = object : ThreadFactory {
    private val idGenerator = AtomicInteger(0)

    override fun newThread(r: Runnable): Thread {
        val thread = Thread(r)
        thread.name = EXECUTOR_THREADNAME_PREFIX + "_" + idGenerator.incrementAndGet()
        return thread
    }
}

// var only for testing purposes, do not alter in production!
private var executor = Executors.newSingleThreadExecutor(threadFactory)
private var rtcDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

/**
 * Overrides how RTC thread calls are executed and dispatched.
 *
 * This should absolutely not be used in production environments and is
 * only to be used for testing.
 */
@VisibleForTesting
fun overrideExecutorAndDispatcher(executorService: ExecutorService, dispatcher: CoroutineDispatcher) {
    executor = executorService
    rtcDispatcher = dispatcher
}

/**
 * Execute [action] on the RTC thread. The PeerConnection API
 * is generally not thread safe, so all actions relating to
 * peer connection objects should go through the RTC thread.
 *
 * @suppress
 */
internal fun <T> executeOnRTCThread(action: () -> T) {
    if (Thread.currentThread().name.startsWith(EXECUTOR_THREADNAME_PREFIX)) {
        action()
    } else {
        executor.submit(action)
    }
}

/**
 * Execute [action] synchronously on the RTC thread. The PeerConnection API
 * is generally not thread safe, so all actions relating to
 * peer connection objects should go through the RTC thread.
 *
 * @suppress
 */
internal fun <T> executeBlockingOnRTCThread(action: () -> T): T {
    return if (Thread.currentThread().name.startsWith(EXECUTOR_THREADNAME_PREFIX)) {
        action()
    } else {
        executor.submit(action).get()
    }
}

/**
 * Launch [action] synchronously on the RTC thread. The PeerConnection API
 * is generally not thread safe, so all actions relating to
 * peer connection objects should go through the RTC thread.
 */
internal suspend fun <T> launchBlockingOnRTCThread(action: suspend CoroutineScope.() -> T): T = coroutineScope {
    return@coroutineScope if (Thread.currentThread().name.startsWith(EXECUTOR_THREADNAME_PREFIX)) {
        this.action()
    } else {
        async(rtcDispatcher) {
            this.action()
        }.await()
    }
}
