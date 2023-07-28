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

package io.livekit.android.util

import android.util.Log

enum class LoggingLevel {
    VERBOSE,
    INFO,
    DEBUG,
    WARN,
    ERROR,
    WTF,
    OFF;

    fun toAndroidLogPriority(): Int {
        return when (this) {
            VERBOSE -> Log.VERBOSE
            INFO -> Log.INFO
            DEBUG -> Log.DEBUG
            WARN -> Log.WARN
            ERROR -> Log.ERROR
            WTF -> Log.ERROR
            OFF -> 0
        }
    }
}

