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

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remembers whether `liblivekit_uniffi` could be loaded, for every subsystem built on it.
 *
 * Injected from [io.livekit.android.dagger.UniffiModule] and scoped to the component, so data
 * streams and both data track managers in a [io.livekit.android.room.Room] share one instance.
 *
 * @suppress
 */
class UniffiNativeLibrary {

    private val unavailable = AtomicBoolean(false)

    /**
     * Whether a previous [createOrNull] has already found the library unloadable.
     */
    val isUnavailable: Boolean
        get() = unavailable.get()

    /**
     * Builds an FFI object, or returns null if the native library is missing or unloadable.
     *
     * [subsystem] names the caller for the log line ("Data streams", "Data tracks"); it is only
     * ever the first failing caller that is named, since the rest never get as far as trying.
     *
     * Callers are expected to hold whatever lock guards their own manager field. This takes no
     * lock of its own -- the latch is atomic and the JVM already serializes the class
     * initialization that does the actual loading -- so it cannot participate in a lock cycle.
     */
    fun <T> createOrNull(subsystem: String, create: () -> T): T? {
        if (unavailable.get()) {
            return null
        }
        return try {
            create()
        } catch (e: LinkageError) {
            // LinkageError, not UnsatisfiedLinkError: only the thread that actually ran the failed
            // class initializer sees that. Every later caller -- and the losers of a concurrent
            // race, since two threads can be inside create() at once -- gets NoClassDefFoundError
            // instead. Catching only the former would let all of them through.
            //
            // Both are logged, the first loudly and any concurrent one quietly. HotSpot chains the
            // original cause onto the NoClassDefFoundError, but that is an implementation detail
            // rather than something the spec promises, so this does not rely on the quiet ones
            // carrying a diagnosis.
            if (unavailable.compareAndSet(false, true)) {
                LKLog.e(e) { "$subsystem unavailable: the LiveKit native library failed to load." }
            } else {
                LKLog.d(e) { "$subsystem also saw the LiveKit native library fail to load." }
            }
            null
        }
    }
}
