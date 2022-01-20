package io.livekit.android.util

import android.util.Log
import io.livekit.android.LiveKit
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import timber.log.Timber

/**
 * Add this rule to a test class to turn on logs.
 */
class LoggingRule : TestRule {

    val logTree = object : Timber.DebugTree() {
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
            Timber.plant(logTree)
            LiveKit.loggingLevel = LoggingLevel.VERBOSE
            base.evaluate()
            Timber.uproot(logTree)
            LiveKit.loggingLevel = oldLoggingLevel
        }
    }
}