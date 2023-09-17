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

import android.content.Context
import android.net.TrafficStats
import io.livekit.android.util.LKLog
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

internal class NetworkMonitor(private val context: Context) {

    private lateinit var coroutineContext: CoroutineContext
    private lateinit var scope: CoroutineScope
    fun start() {
        coroutineContext = SupervisorJob() + Dispatchers.IO
        scope = CoroutineScope(coroutineContext)
        scope.launch {
            val uid = context.packageManager.getApplicationInfo(context.packageName, 0).uid

            var prevTxBytes = TrafficStats.getUidTxBytes(uid)
            var emaTxBytes = 0L
            while (this.isActive) {
                val totalTxBytes = TrafficStats.getUidTxBytes(uid)
                val intervalTxBytes = totalTxBytes - prevTxBytes
                prevTxBytes = totalTxBytes
                emaTxBytes = emaTxBytes / 2 + intervalTxBytes / 2

                LKLog.v { "send rate: ${convertBytesToReadableString(emaTxBytes)}" }

                delay(1000)
            }
        }
    }

    private fun convertBytesToReadableString(bytes: Long): String {
        var num = bytes.toFloat()
        var level = 0
        while (num >= 1024 && level < 2) {
            num /= 1024
            level++
        }

        // MBps should be way more than enough.
        val suffix = when (level) {
            0 -> "Bps"
            1 -> "kBps"
            2 -> "MBps"
            else -> throw IllegalStateException("this shouldn't happen. level = $level")
        }

        return "$num $suffix"
    }

    fun stop() {
        coroutineContext.cancel()
    }
}
