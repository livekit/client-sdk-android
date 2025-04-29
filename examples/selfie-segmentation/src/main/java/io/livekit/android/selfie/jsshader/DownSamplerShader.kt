package io.livekit.android.selfie.jsshader

import android.opengl.GLES20
import io.livekit.android.util.LKLog
import livekit.org.webrtc.GlShader
import livekit.org.webrtc.GlTextureFrameBuffer
import livekit.org.webrtc.GlUtil

private const val DOWNSAMPLER_VERTEX_SHADER_SOURCE = """
attribute vec4 in_pos;
attribute vec4 in_tc;
uniform mat4 tex_mat;
varying vec2 v_uv;
void main() {
    v_uv = ((tex_mat * in_tc).xy + 1.0) * 0.5;
    gl_Position = in_pos;
}
"""

private const val DOWNSAMPLER_FRAGMENT_SHADER_SOURCE = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 v_uv;
uniform samplerExternalOES u_texture;

void main() {
    gl_FragColor = texture2D(u_texture, v_uv);
}
"""

// Vertex coordinates in Normalized Device Coordinates, i.e. (-1, -1) is bottom-left and (1, 1)
// is top-right.
val FULL_RECTANGLE_BUFFER = GlUtil.createFloatBuffer(
    floatArrayOf(
        -1.0f, -1.0f,  // Bottom left.
        1.0f, -1.0f,  // Bottom right.
        -1.0f, 1.0f,  // Top left.
        1.0f, 1.0f,  // Top right.
    ),
)

// Texture coordinates - (0, 0) is bottom-left and (1, 1) is top-right.
val FULL_RECTANGLE_TEXTURE_BUFFER = GlUtil.createFloatBuffer(
    floatArrayOf(
        0.0f, 0.0f,  // Bottom left.
        1.0f, 0.0f,  // Bottom right.
        0.0f, 1.0f,  // Top left.
        1.0f, 1.0f,  // Top right.
    ),
)

fun createDownSampler(): DownSamplerShader {
    val textureFrameBuffer = GlTextureFrameBuffer(GLES20.GL_RGBA)
    val shader = GlShader(DOWNSAMPLER_VERTEX_SHADER_SOURCE, DOWNSAMPLER_FRAGMENT_SHADER_SOURCE)

    return DownSamplerShader(
        shader = shader,
        textureFrameBuffer = textureFrameBuffer,
        texMatrixLocation = shader.getUniformLocation(VERTEX_SHADER_TEX_MAT_NAME),
        inPosLocation = shader.getAttribLocation(VERTEX_SHADER_POS_COORD_NAME),
        inTcLocation = shader.getAttribLocation(VERTEX_SHADER_TEX_COORD_NAME),
        texture = shader.getUniformLocation("u_texture"),
    )
}

data class DownSamplerShader(
    val shader: GlShader,
    val textureFrameBuffer: GlTextureFrameBuffer,
    val texMatrixLocation: Int,
    val inPosLocation: Int,
    val inTcLocation: Int,
    val texture: Int,
) {

    fun prepare(viewportWidth: Int, viewportHeight: Int) {
        textureFrameBuffer.setSize(viewportWidth, viewportHeight)
    }

    fun applyDownsampling(inputTexture: Int, viewportWidth: Int, viewportHeight: Int, texMatrix: FloatArray): Int {

        LKLog.e { "applyDownsampling" }
        shader.useProgram()

        ShaderUtil.loadCoordMatrix(inPosLocation = inPosLocation, inTcLocation = inTcLocation, texMatrixLocation = texMatrixLocation, texMatrix = texMatrix)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, textureFrameBuffer.frameBufferId)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        GLES20.glUniform1i(texture, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // cleanup
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        GlUtil.checkNoGLES2Error("DownSamplerShader.applyDownsampling");
        return textureFrameBuffer.textureId
    }
}
