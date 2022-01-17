package io.livekit.android.util

import android.util.Log
import io.livekit.android.LiveKit
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import timber.log.Timber

class LoggingRule : TestRule {

    val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val priorityChar = when (priority) {
                Log.VERBOSE -> "v"
                Log.DEBUG -> "d"
                Log.INFO -> "i"
                Log.WARN -> "w"
                Log.ERROR -> "e"
                Log.ASSERT -> "a"
                else -> "?"
            }

            println("$priorityChar: $tag: $message")
            if (t != null) {
                println(t.toString())
            }
        }

    }

    override fun apply(base: Statement, description: Description?) = object : Statement() {
        override fun evaluate() {
            val oldLoggingLevel = LiveKit.loggingLevel
            LiveKit.loggingLevel = LoggingLevel.VERBOSE
            Timber.plant(logTree)
            base.evaluate()
            Timber.uproot(logTree)
            LiveKit.loggingLevel = oldLoggingLevel
        }
    }
}