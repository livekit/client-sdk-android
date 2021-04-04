@file:Suppress("unused")

package io.livekit.android.util

/**
 * Forces a when expression to be exhaustive only.
 */
internal fun Unit.safe() {}

/**
 * Forces a when expression to be exhaustive only.
 */
internal fun Nothing?.safe() {}

/**
 * Forces a when expression to be exhaustive only.
 */
internal fun Any?.safe() {}