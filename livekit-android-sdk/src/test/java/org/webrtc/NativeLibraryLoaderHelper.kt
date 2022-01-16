package org.webrtc

object NativeLibraryLoaderTestHelper {
    fun initialize() {
        if (!NativeLibrary.isLoaded()) {
            NativeLibrary.initialize({ true }, "")
        }
    }
}