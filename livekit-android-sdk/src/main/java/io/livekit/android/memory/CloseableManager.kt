/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android.memory

import java.io.Closeable

/**
 * @suppress
 */
class CloseableManager : Closeable {

    private var isClosed = false
    private val resources = mutableMapOf<Any, Closeable>()

    @Synchronized
    fun registerResource(key: Any, closer: Closeable) {
        if (isClosed) {
            closer.close()
            return
        } else {
            resources[key] = closer
        }
    }

    @Synchronized
    fun registerClosable(closable: Closeable) {
        if (isClosed) {
            closable.close()
            return
        } else {
            resources[closable] = closable
        }
    }

    @Synchronized
    fun unregisterResource(key: Any): Closeable? {
        return resources.remove(key)
    }

    @Synchronized
    override fun close() {
        isClosed = true
        resources.values.forEach { it.close() }
        resources.clear()
    }
}
