/*
 * Copyright 2025 LiveKit, Inc.
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

import android.os.SystemClock
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * @suppress
 */
class TTLMap<K, V>(
    val ttl: Duration,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : MutableMap<K, V> {

    private data class TTLItem<V>(val value: V, val expiresAt: Long)

    private val map = mutableMapOf<K, TTLItem<V>>()
    private val lastCleanup = getNow()

    override fun get(key: K): V? {
        val item = map[key]

        if (item == null) {
            return null
        }

        if (item.expiresAt < getNow()) {
            map.remove(key)
            return null
        }
        return item.value
    }

    override val size: Int
        get() {
            cleanup()
            return map.size
        }

    override fun containsKey(key: K): Boolean = get(key) != null
    override fun containsValue(value: V): Boolean = values.contains(value)

    override fun isEmpty(): Boolean {
        cleanup()
        return map.isEmpty()
    }

    private data class MutableEntry<K, V>(override val key: K, override var value: V) : MutableMap.MutableEntry<K, V> {
        override fun setValue(newValue: V): V {
            val old = this.value
            this.value = newValue
            return old
        }
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() {
            cleanup()
            return map.entries
                .map { (key, value) -> MutableEntry(key, value.value) }
                .toMutableSet()
        }

    override val keys: MutableSet<K>
        get() {
            cleanup()
            return map.keys
        }

    override val values: MutableCollection<V>
        get() {
            cleanup()
            return map.values
                .map { (value, _) -> value }
                .toMutableList()
        }

    override fun clear() {
        map.clear()
    }

    override fun put(key: K, value: V): V? {
        val now = getNow()
        val ttlMs = ttl.toLong(DurationUnit.MILLISECONDS)
        if (now - lastCleanup > ttlMs / 2) {
            cleanup()
        }

        val expiresAt = now + ttlMs

        map[key] = TTLItem(value, expiresAt)
        return value
    }

    override fun putAll(from: Map<out K, V>) {
        from.iterator().forEach { (key, value) ->
            put(key, value)
        }
    }

    override fun remove(key: K): V? {
        return map.remove(key)?.value
    }

    fun cleanup() {
        val now = getNow()

        val iterator = map.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (entry.expiresAt < now) {
                iterator.remove()
            }
        }
    }

    private fun getNow() = clock()
}
