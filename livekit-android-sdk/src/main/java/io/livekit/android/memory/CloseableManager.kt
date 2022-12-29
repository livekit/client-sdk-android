package io.livekit.android.memory

import java.io.Closeable

/**
 * @hide
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