package io.livekit.android.util

import io.livekit.android.util.LoggingLevel.*
import timber.log.Timber

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

internal class LKLog {

    companion object {
        var loggingLevel = OFF


        /** Log a verbose exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun v(t: Throwable? = null, message: () -> String) =
            log(VERBOSE) { Timber.v(t, message()) }

        @JvmStatic
        inline fun v(t: Throwable?) = log(VERBOSE) { Timber.v(t) }

        /** Log a debug exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun d(t: Throwable? = null, message: () -> String) =
            log(DEBUG) { Timber.d(t, message()) }

        @JvmStatic
        inline fun d(t: Throwable?) = log(DEBUG) { Timber.d(t) }

        /** Log an info exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun i(t: Throwable? = null, message: () -> String) =
            log(INFO) { Timber.i(t, message()) }

        @JvmStatic
        inline fun i(t: Throwable?) = log(INFO) { Timber.i(t) }

        /** Log a warning exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun w(t: Throwable? = null, message: () -> String) =
            log(WARN) { Timber.w(t, message()) }

        @JvmStatic
        inline fun w(t: Throwable?) = log(WARN) { Timber.w(t) }

        /** Log an error exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun e(t: Throwable? = null, message: () -> String) =
            log(ERROR) { Timber.e(t, message()) }

        @JvmStatic
        inline fun e(t: Throwable?) = log(ERROR) { Timber.e(t) }

        /** Log an assert exception and a message that will be evaluated lazily when the message is printed */
        @JvmStatic
        inline fun wtf(t: Throwable? = null, message: () -> String) =
            log(WTF) { Timber.wtf(t, message()) }

        @JvmStatic
        inline fun wtf(t: Throwable?) = log(WTF) { Timber.wtf(t) }

        /** @suppress */
        internal inline fun log(loggingLevel: LoggingLevel, block: () -> Unit) {
            if (loggingLevel >= LKLog.loggingLevel && Timber.treeCount() > 0) block()
        }
    }
}
