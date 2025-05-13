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

import android.opengl.GLES20
import livekit.org.webrtc.GlShader
import livekit.org.webrtc.GlTextureFrameBuffer
import livekit.org.webrtc.GlUtil

private const val BLUR_FRAGMENT_SHADER = """#version 300 es
precision mediump float;

in vec2 texCoords;

uniform sampler2D u_texture;
uniform vec2 u_texelSize;
uniform vec2 u_direction;
uniform float u_radius;

out vec4 fragColor;

void main() {
    float sigma = u_radius;
    float twoSigmaSq = 2.0 * sigma * sigma;
    float totalWeight = 0.0;
    vec3 result = vec3(0.0);
    const int MAX_SAMPLES = 16;
    int radius = int(min(float(MAX_SAMPLES), ceil(u_radius)));

    for (int i = -MAX_SAMPLES; i <= MAX_SAMPLES; ++i) {
        float offset = float(i);
        if (abs(offset) > float(radius)) continue;
        float weight = exp(-(offset * offset) / twoSigmaSq);
        vec2 sampleCoord = texCoords + u_direction * u_texelSize * offset;
        result += texture(u_texture, sampleCoord).rgb * weight;
        totalWeight += weight;
    }

    fragColor = vec4(result / totalWeight, 1.0);
}
"""

internal fun createBlurShader(): BlurShader {
    val shader = GlShader(CONSTANT_VERTEX_SHADER_SOURCE, BLUR_FRAGMENT_SHADER)

    return BlurShader(
        shader = shader,
        texMatrixLocation = 0,
        inPosLocation = shader.getAttribLocation(VERTEX_SHADER_POS_COORD_NAME),
        inTcLocation = 0,
        texture = shader.getUniformLocation("u_texture"),
        texelSize = shader.getUniformLocation("u_texelSize"),
        direction = shader.getUniformLocation("u_direction"),
        radius = shader.getUniformLocation("u_radius"),
    )
}

internal data class BlurShader(
    val shader: GlShader,
    val inPosLocation: Int,
    val inTcLocation: Int,
    val texMatrixLocation: Int,
    val texture: Int,
    val texelSize: Int,
    val direction: Int,
    val radius: Int,
) {
    fun release() {
        shader.release()
    }

    fun applyBlur(
        inputTextureId: Int,
        blurRadius: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        processFrameBuffer: Pair<GlTextureFrameBuffer, GlTextureFrameBuffer>,
        texMatrix: FloatArray? = null,
    ): Int {
        shader.useProgram()

        // Upload the texture coordinates.
        ShaderUtil.loadCoordMatrix(
            inPosLocation = inPosLocation,
            inPosFloats = FULL_RECTANGLE_BUFFER,
            inTcLocation = inTcLocation,
            inTcFloats = if (texMatrix != null) FULL_RECTANGLE_TEXTURE_BUFFER else null,
            texMatrixLocation = texMatrixLocation,
            texMatrix = texMatrix,
        )

        GlUtil.checkNoGLES2Error("BlurShader.loadCoordMatrix")

        processFrameBuffer.first.setSize(viewportWidth, viewportHeight)
        processFrameBuffer.second.setSize(viewportWidth, viewportHeight)
        GlUtil.checkNoGLES2Error("BlurShader.updateFrameBufferSizes")

        val texelWidth = 1.0f / viewportWidth
        val texelHeight = 1.0f / viewportHeight

        // First pass - horizontal blur
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, processFrameBuffer.first.frameBufferId)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)

        GlUtil.checkNoGLES2Error("BlurShader.glBindFramebuffer")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        GlUtil.checkNoGLES2Error("BlurShader.bind oes")
        GLES20.glUniform1i(texture, 0)
        GLES20.glUniform2f(texelSize, texelWidth, texelHeight)
        GLES20.glUniform2f(direction, 1.0f, 0.0f) // Horizontal
        GLES20.glUniform1f(radius, blurRadius)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkNoGLES2Error("BlurShader.GL_TRIANGLE_STRIP")

        // cleanup
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // Second pass - vertical blur
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, processFrameBuffer.second.frameBufferId)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, processFrameBuffer.first.textureId)
        GLES20.glUniform1i(texture, 0)
        GLES20.glUniform2f(texelSize, texelWidth, texelHeight)
        GLES20.glUniform2f(direction, 0.0f, 1.0f) // Vertical
        GLES20.glUniform1f(radius, blurRadius)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GlUtil.checkNoGLES2Error("BlurShader.GL_TRIANGLE_STRIP2")
        // cleanup
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        GlUtil.checkNoGLES2Error("BlurShader.applyBlur")
        return processFrameBuffer.second.textureId
    }
}
