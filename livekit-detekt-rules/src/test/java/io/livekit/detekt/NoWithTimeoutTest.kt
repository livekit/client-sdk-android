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

package io.livekit.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class NoWithTimeoutTest {

    private val subject = NoWithTimeout(Config.empty)

    @Test
    fun `reports withTimeout with explicit import`() {
        val code = """
            import kotlinx.coroutines.withTimeout

            suspend fun f() {
                withTimeout(1L) { }
            }
        """.trimIndent()
        val findings = subject.lint(code)
        assertEquals(1, findings.size)
    }

    @Test
    fun `reports withTimeout with Int millis and delay in block like CoroutineUtil`() {
        val code = """
            import kotlinx.coroutines.delay
            import kotlinx.coroutines.withTimeout

            suspend fun testTimeout() {
                withTimeout(1000) {
                    delay(2000)
                }
            }
        """.trimIndent()
        val findings = subject.lint(code)
        assertEquals(1, findings.size)
    }

    @Test
    fun `source text fallback matches import when PSI import list is typical`() {
        // Same as CoroutineUtil: explicit import line + withTimeout(1000) { delay }
        val code = """
            package io.example
            import kotlinx.coroutines.delay
            import kotlinx.coroutines.withTimeout

            suspend fun testTimeout() {
                withTimeout(1000) { delay(2000) }
            }
        """.trimIndent()
        assertEquals(1, subject.lint(code).size)
    }

    @Test
    fun `reports withTimeout with star import`() {
        val code = """
            import kotlinx.coroutines.*

            suspend fun f() {
                withTimeout(1L) { }
            }
        """.trimIndent()
        val findings = subject.lint(code)
        assertEquals(1, findings.size)
    }

    @Test
    fun `reports each withTimeout in the same file`() {
        val code = """
            import kotlinx.coroutines.withTimeout

            suspend fun a() { withTimeout(1L) { } }
            suspend fun b() { withTimeout(2L) { } }
        """.trimIndent()
        val findings = subject.lint(code)
        assertEquals(2, findings.size)
    }

    @Test
    fun `ignores withTimeoutOrNull`() {
        val code = """
            import kotlinx.coroutines.withTimeoutOrNull

            suspend fun f() {
                withTimeoutOrNull(1L) { }
            }
        """.trimIndent()
        val findings = subject.lint(code)
        assertEquals(0, findings.size)
    }

    @Test
    fun `reports unqualified withTimeout without imports`() {
        val code = """
            class C {
                fun f() {
                    withTimeout(1L) { }
                }
            }
        """.trimIndent()
        val findings = subject.lint(code)
        assertEquals(1, findings.size)
    }
}
