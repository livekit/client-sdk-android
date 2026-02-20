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

package io.livekit.android.test.util

import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.util.LKDebugTree
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Add this rule to a test class to turn on logs.
 */
class LoggingRule : TestRule {

    companion object {

        val logger = object : LKLog.Logger {
            override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                printlnTree.prepareLog(priority.toAndroidLogPriority(), t, message)
            }
        }
        val printlnTree = object : LKDebugTree() {
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
    }

    override fun apply(base: Statement, description: Description?) = object : Statement() {
        override fun evaluate() {
            val oldLoggingLevel = LiveKit.loggingLevel
            val oldLogger = LiveKit.logger
            LiveKit.loggingLevel = LoggingLevel.VERBOSE
            LiveKit.logger = logger
            base.evaluate()
            LiveKit.loggingLevel = oldLoggingLevel
            LiveKit.logger = oldLogger
        }
    }
}
