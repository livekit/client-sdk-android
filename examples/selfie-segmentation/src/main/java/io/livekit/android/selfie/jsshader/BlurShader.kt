package io.livekit.android.selfie.jsshader

import android.opengl.GLES11Ext
import android.opengl.GLES20
import livekit.org.webrtc.GlShader
import livekit.org.webrtc.GlTextureFrameBuffer

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

fun createBlurShader(): BlurShader {
    val shader = GlShader(DEFAULT_VERTEX_SHADER_SOURCE, BLUR_FRAGMENT_SHADER)

    return BlurShader(
        shader = shader,
        texMatrixLocation = shader.getUniformLocation(VERTEX_SHADER_TEX_MAT_NAME),
        inPosLocation = shader.getAttribLocation(VERTEX_SHADER_POS_COORD_NAME),
        inTcLocation = shader.getAttribLocation(VERTEX_SHADER_TEX_COORD_NAME),
        texture = shader.getUniformLocation("u_texture"),
        texelSize = shader.getUniformLocation("u_texelSize"),
        direction = shader.getUniformLocation("u_direction"),
        radius = shader.getUniformLocation("u_radius"),
    )
}


data class BlurShader(
    val shader: GlShader,
    val inPosLocation: Int,
    val inTcLocation: Int,
    val texMatrixLocation: Int,
    val texture: Int,
    val texelSize: Int,
    val direction: Int,
    val radius: Int,
) {
    fun applyBlur(
        inputTextureId: Int,
        blurRadius: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        processFrameBuffer: Pair<GlTextureFrameBuffer, GlTextureFrameBuffer>,
        texMatrix: FloatArray
    ): Int {
        shader.useProgram()

        ShaderUtil.loadCoordMatrix(inPosLocation = inPosLocation, inTcLocation = inTcLocation, texMatrixLocation = texMatrixLocation, texMatrix = texMatrix)

        val texelWidth = 1.0f / viewportWidth
        val texelHeight = 1.0f / viewportHeight

        // First pass - horizontal blur
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, processFrameBuffer.first.frameBufferId)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glUniform1i(texture, 0)
        GLES20.glUniform2f(texelSize, texelWidth, texelHeight)
        GLES20.glUniform2f(direction, 1.0f, 0.0f) // Horizontal
        GLES20.glUniform1f(radius, blurRadius)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Second pass - vertical blur
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, processFrameBuffer.second.textureId)
        GLES20.glUniform1i(texture, 0)
        GLES20.glUniform2f(direction, 0.0f, 1.0f) // Vertical

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return processFrameBuffer.second.textureId
    }
}
