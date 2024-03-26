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

package io.livekit.android.test.mock

import android.graphics.SurfaceTexture
import android.view.Surface
import livekit.org.webrtc.EglBase

class MockEglBase(
    private val eglContext: EglBase.Context = EglBase.Context { 0 },
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
