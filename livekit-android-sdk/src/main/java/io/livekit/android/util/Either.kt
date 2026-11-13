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

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * @suppress
 */
sealed class Either<out A, out B> {
    class Left<out A>(val value: A) : Either<A, Nothing>()
    class Right<out B>(val value: B) : Either<Nothing, B>()
}

internal fun <A> Either<A, String?>?.nullSafe(): Either<A, String?> {
    return this ?: Either.Right("null")
}

/**
 * Whether this is an [Either.Left]. A true result smart-casts this to [Either.Left],
 * and a false result to [Either.Right].
 */
@OptIn(ExperimentalContracts::class)
internal fun <A, B> Either<A, B>.isLeft(): Boolean {
    contract {
        returns(true) implies (this@isLeft is Either.Left<A>)
        returns(false) implies (this@isLeft is Either.Right<B>)
    }
    return this is Either.Left
}

/**
 * Whether this is an [Either.Right]. A true result smart-casts this to [Either.Right],
 * and a false result to [Either.Left].
 */
@OptIn(ExperimentalContracts::class)
internal fun <A, B> Either<A, B>.isRight(): Boolean {
    // Kotlin 1.9 inverts this contract when reading it from compiled code unless the
    // Either.Left clause comes first. EitherTest checks the narrowing from another module.
    contract {
        returns(false) implies (this@isRight is Either.Left<A>)
        returns(true) implies (this@isRight is Either.Right<B>)
    }
    return this is Either.Right
}
