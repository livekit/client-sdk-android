/*
 * Copyright 2026 LiveKit, Inc.
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

import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.util.regex.Pattern

/**
 * This is a modified version of DebugTree from JakeWharton/timber for LiveKit.
 * https://github.com/JakeWharton/timber
 */

/**
 * A tree for debug builds. Automatically infers the tag from the calling class.
 * @suppress
 * */
open class LKDebugTree {

    private val fqcnIgnore =
        listOf(
            LKDebugTree::class.java.name,
            LKLog.defaultLogger::class.java.name,
        )

    val tag: String?
        get() =
            Throwable()
                .stackTrace
                .first { it.className !in fqcnIgnore }
                .let(::createStackElementTag)

    fun prepareLog(priority: Int, t: Throwable?, message: String?) {
        // Consume tag even when message is not loggable so that next message is correctly tagged.
        val tag = tag

        var message = message
        if (message.isNullOrEmpty()) {
            if (t == null) {
                return // Swallow message if it's null and there's no throwable.
            }
            message = getStackTraceString(t)
        } else {
            if (t != null) {
                message += "\n" + getStackTraceString(t)
            }
        }

        log(priority, tag, message, t)
    }

    private fun getStackTraceString(t: Throwable): String {
        // Don't replace this with Log.getStackTraceString() - it hides
        // UnknownHostException, which is not what we want.
        val sw = StringWriter(256)
        val pw = PrintWriter(sw, false)
        t.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    /**
     * Extract the tag which should be used for the message from the `element`. By default this will
     * use the class name without any anonymous class suffixes (e.g., `Foo$1` becomes `Foo`).
     *
     * Note: This will not be called if a [manual tag][.tag] was specified.
     */
    private fun createStackElementTag(element: StackTraceElement): String? {
        var tag = element.className.substringAfterLast('.')
        val m = ANONYMOUS_CLASS.matcher(tag)
        if (m.find()) {
            tag = m.replaceAll("")
        }
        // Tag length limit was removed in API 26.
        return if (tag.length <= MAX_TAG_LENGTH || Build.VERSION.SDK_INT >= 26) {
            tag
        } else {
            tag.take(MAX_TAG_LENGTH)
        }
    }

    /**
     * Break up `message` into maximum-length chunks (if needed) and send to either
     * [Log.println()][Log.println] or [Log.wtf()][Log.wtf] for logging.
     *
     * {@inheritDoc}
     */
    open fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (message.length < MAX_LOG_LENGTH) {
            if (priority == Log.ASSERT) {
                Log.wtf(tag, message)
            } else {
                Log.println(priority, tag, message)
            }
            return
        }

        // Split by line, then ensure each line can fit into Log's maximum length.
        var i = 0
        val length = message.length
        while (i < length) {
            var newline = message.indexOf('\n', i)
            newline = if (newline != -1) newline else length
            do {
                val end = newline.coerceAtMost(i + MAX_LOG_LENGTH)
                val part = message.substring(i, end)
                if (priority == Log.ASSERT) {
                    Log.wtf(tag, part)
                } else {
                    Log.println(priority, tag, part)
                }
                i = end
            } while (i < newline)
            i++
        }
    }

    companion object {
        private const val MAX_LOG_LENGTH = 4000
        private const val MAX_TAG_LENGTH = 23
        private val ANONYMOUS_CLASS = Pattern.compile("(\\$\\d+)+$")
    }
}
