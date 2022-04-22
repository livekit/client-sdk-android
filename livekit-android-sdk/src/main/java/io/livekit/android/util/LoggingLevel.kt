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

