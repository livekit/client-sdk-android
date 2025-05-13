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

package io.livekit.android.track.processing.video.shader

import android.opengl.GLES11Ext
import android.opengl.GLES20
import livekit.org.webrtc.GlShader
import livekit.org.webrtc.GlUtil

private const val COMPOSITE_FRAGMENT_SHADER_SOURCE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 texCoords;
uniform sampler2D background;
uniform samplerExternalOES frame;
uniform sampler2D mask;
out vec4 fragColor;

void main() {

    vec4 frameTex = texture(frame, texCoords);
    vec4 bgTex = texture(background, texCoords);

    float maskVal = texture(mask, texCoords).r;

    // Compute screen-space gradient to detect edge sharpness
    float grad = length(vec2(dFdx(maskVal), dFdy(maskVal)));

    float edgeSoftness = 6.0; // higher = softer

    // Create a smooth edge around binary transition
    float smoothAlpha = smoothstep(0.5 - grad * edgeSoftness, 0.5 + grad * edgeSoftness, maskVal);

    // Optional: preserve frame alpha, or override as fully opaque
    vec4 blended = mix(bgTex, vec4(frameTex.rgb, 1.0), 0.0 + smoothAlpha);

    fragColor = blended;

}
"""

internal fun createCompsiteShader(): CompositeShader {
    val shader = GlShader(DEFAULT_VERTEX_SHADER_SOURCE, COMPOSITE_FRAGMENT_SHADER_SOURCE)

    return CompositeShader(
        shader = shader,
        texMatrixLocation = shader.getUniformLocation(VERTEX_SHADER_TEX_MAT_NAME),
        inPosLocation = shader.getAttribLocation(VERTEX_SHADER_POS_COORD_NAME),
        inTcLocation = shader.getAttribLocation(VERTEX_SHADER_TEX_COORD_NAME),
        mask = shader.getUniformLocation("mask"),
        frame = shader.getUniformLocation("frame"),
        background = shader.getUniformLocation("background"),
    )
}

internal data class CompositeShader(
    val shader: GlShader,
    val inPosLocation: Int,
    val inTcLocation: Int,
    val texMatrixLocation: Int,
    val mask: Int,
    val frame: Int,
    val background: Int,
) {
    fun renderComposite(
        backgroundTextureId: Int,
        frameTextureId: Int,
        maskTextureId: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        texMatrix: FloatArray,
    ) {
        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Set up uniforms for the composite shader
        shader.useProgram()

        ShaderUtil.loadCoordMatrix(
            inPosLocation = inPosLocation,
            inPosFloats = FULL_RECTANGLE_BUFFER,
            inTcLocation = inTcLocation,
            inTcFloats = FULL_RECTANGLE_TEXTURE_BUFFER,
            texMatrixLocation = texMatrixLocation,
            texMatrix = texMatrix,
        )
        GlUtil.checkNoGLES2Error("loadCoordMatrix")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTextureId)
        GLES20.glUniform1i(background, 0)
        GlUtil.checkNoGLES2Error("GL_TEXTURE0")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameTextureId)
        GLES20.glUniform1i(frame, 1)
        GlUtil.checkNoGLES2Error("GL_TEXTURE1")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        GLES20.glUniform1i(mask, 2)
        GlUtil.checkNoGLES2Error("GL_TEXTURE2")

        // Draw composite
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkNoGLES2Error("GL_TRIANGLE_STRIP")

        // Cleanup
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GlUtil.checkNoGLES2Error("renderComposite")
    }

    fun release() {
        shader.release()
    }
}
