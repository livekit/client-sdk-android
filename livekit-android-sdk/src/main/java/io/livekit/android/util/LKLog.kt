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

package io.livekit.android.util

import io.livekit.android.util.LoggingLevel.DEBUG
import io.livekit.android.util.LoggingLevel.ERROR
import io.livekit.android.util.LoggingLevel.INFO
import io.livekit.android.util.LoggingLevel.OFF
import io.livekit.android.util.LoggingLevel.VERBOSE
import io.livekit.android.util.LoggingLevel.WARN
import io.livekit.android.util.LoggingLevel.WTF

/*
Copyright 2017-2018 AJ Alt
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Original repo can be found at: https://github.com/ajalt/LKLogkt
 */


private val DEBUG_TREE = LKDebugTree()

/**
 * Internal logger for LiveKit
 *
 * @suppress
 */
@Suppress("NOTHING_TO_INLINE", "unused")
class LKLog {

    interface Logger {
        fun log(priority: LoggingLevel, t: Throwable?, message: String)
    }
    companion object {
        val defaultLogger: Logger = object : Logger {
            override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                DEBUG_TREE.prepareLog(priority.toAndroidLogPriority(), t, message)
            }
        }

        var logger: Logger? = defaultLogger

        var loggingLevel = OFF

        /** Log a verbose exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun v(t: Throwable? = null, crossinline message: () -> String) = log(VERBOSE, t, message)

        @JvmStatic
        inline fun v(t: Throwable?) = log(VERBOSE, t) { "" }

        /** Log a debug exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun d(t: Throwable? = null, crossinline message: () -> String) = log(DEBUG, t, message)

        @JvmStatic
        inline fun d(t: Throwable?) = log(DEBUG, t) { "" }

        /** Log an info exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun i(t: Throwable? = null, crossinline message: () -> String) = log(INFO, t, message)

        @JvmStatic
        inline fun i(t: Throwable?) = log(INFO, t) { "" }

        /** Log a warning exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun w(t: Throwable? = null, crossinline message: () -> String) = log(WARN, t, message)

        @JvmStatic
        inline fun w(t: Throwable?) = log(WARN, t) { "" }

        /** Log an error exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun e(t: Throwable? = null, crossinline message: () -> String) = log(ERROR, t, message)

        @JvmStatic
        inline fun e(t: Throwable?) = log(ERROR, t) { "" }

        /** Log an assert exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun wtf(t: Throwable? = null, crossinline message: () -> String) = log(WTF, t, message)

        @JvmStatic
        inline fun wtf(t: Throwable?) = log(WTF, t) { "" }

        /** @suppress */
        inline fun log(loggingLevel: LoggingLevel, t: Throwable? = null, crossinline message: (() -> String)) {
            if (loggingLevel >= LKLog.loggingLevel) {
                logger?.log(loggingLevel, t, message())
            }
        }
    }
}
