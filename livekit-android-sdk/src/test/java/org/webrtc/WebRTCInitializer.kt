package org.webrtc

import android.content.Context
import org.mockito.Mockito

object WebRTCInitializer {
    fun initialize(context: Context = Mockito.mock(Context::class.java)) {
        try {
            ContextUtils.initialize(context)
            NativeLibraryLoaderTestHelper.initialize()
        } catch (e: Throwable) {
            // do nothing. this is expected.
        }
    }
}