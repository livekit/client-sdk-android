package io.livekit.android.mock

import android.graphics.SurfaceTexture
import android.view.Surface
import org.webrtc.EglBase

class MockEglBase(
    private val eglContext: EglBase.Context = EglBase.Context { 0 }
) : EglBase {

    override fun createSurface(p0: Surface?) {}

    override fun createSurface(p0: SurfaceTexture?) {}

    override fun createDummyPbufferSurface() {}

    override fun createPbufferSurface(p0: Int, p1: Int) {}

    override fun getEglBaseContext(): EglBase.Context = eglContext

    override fun hasSurface(): Boolean = false

    override fun surfaceWidth(): Int = 0

    override fun surfaceHeight(): Int = 0

    override fun releaseSurface() {}

    override fun release() {}

    override fun makeCurrent() {}

    override fun detachCurrent() {}

    override fun swapBuffers() {}

    override fun swapBuffers(p0: Long) {}
}