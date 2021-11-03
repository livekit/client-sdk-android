package org.webrtc

object NativeLibraryLoaderTestHelper {
    fun initialize() {
        NativeLibrary.initialize({ true }, "")
    }
}