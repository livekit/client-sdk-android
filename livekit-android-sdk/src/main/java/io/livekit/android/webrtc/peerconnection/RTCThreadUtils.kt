package io.livekit.android.webrtc.peerconnection

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
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

private const val EXECUTOR_THREADNAME_PREFIX = "LK_RTC_THREAD";
private val threadFactory = object : ThreadFactory {
    private val idGenerator = AtomicInteger(0);

    override fun newThread(r: Runnable): Thread {
        val thread = Thread(r);
        thread.name = EXECUTOR_THREADNAME_PREFIX + "_" + idGenerator.incrementAndGet();
        return thread;
    }
}

// var only for testing purposes, do not alter!
private var executor = Executors.newSingleThreadExecutor(threadFactory)
private var rtcDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

@VisibleForTesting
internal fun overrideExecutorAndDispatcher(executorService: ExecutorService, dispatcher: CoroutineDispatcher) {
    executor = executorService
    rtcDispatcher = dispatcher
}

/**
 * Execute [action] on the RTC thread. The PeerConnection API
 * is generally not thread safe, so all actions relating to
 * peer connection objects should go through the RTC thread.
 */
fun <T> executeOnRTCThread(action: () -> T): T {
    return if (Thread.currentThread().name.startsWith(EXECUTOR_THREADNAME_PREFIX)) {
        action()
    } else {
        executor.submit(action).get()
    }
}


/**
 * Launch [action] on the RTC thread. The PeerConnection API
 * is generally not thread safe, so all actions relating to
 * peer connection objects should go through the RTC thread.
 */
suspend fun <T> launchOnRTCThread(action: suspend () -> T): T = coroutineScope {
    return@coroutineScope if (Thread.currentThread().name.startsWith(EXECUTOR_THREADNAME_PREFIX)) {
        action()
    } else {
        val result = async(rtcDispatcher) {
            action()
        }

        result.await()
    }
}
