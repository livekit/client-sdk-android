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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EitherTest {

    private val left: Either<Int, String> = Either.Left(1)
    private val right: Either<Int, String> = Either.Right("error")

    @Test
    fun isLeft() {
        assertTrue(left.isLeft())
        assertFalse(right.isLeft())
    }

    @Test
    fun isRight() {
        assertTrue(right.isRight())
        assertFalse(left.isRight())
    }

    // The tests below only compile if the contracts smart-cast `value` into reach.

    @Test
    fun isLeftSmartCastsToLeft() {
        if (left.isLeft()) {
            val value: Int = left.value
            assertEquals(1, value)
        }
    }

    @Test
    fun isLeftFalseSmartCastsToRight() {
        if (!right.isLeft()) {
            val value: String = right.value
            assertEquals("error", value)
        }
    }

    @Test
    fun isRightSmartCastsToRight() {
        if (right.isRight()) {
            val value: String = right.value
            assertEquals("error", value)
        }
    }

    @Test
    fun isRightFalseSmartCastsToLeft() {
        if (!left.isRight()) {
            val value: Int = left.value
            assertEquals(1, value)
        }
    }

    @Test
    fun earlyReturnOfRightKeepsLeftType() {
        fun firstStep(): Either<Int, String> = right

        fun pipeline(): Either<Long, String> {
            val step = firstStep()
            if (step.isRight()) {
                return step
            }
            return Either.Left(step.value.toLong())
        }

        val result = pipeline()
        assertTrue(result.isRight())
        assertEquals("error", (result as Either.Right).value)
    }
}
