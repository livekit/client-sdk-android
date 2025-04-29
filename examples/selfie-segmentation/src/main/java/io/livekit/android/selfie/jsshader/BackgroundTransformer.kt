package io.livekit.android.selfie.jsshader

import android.opengl.GLES20
import io.livekit.android.util.LKLog
import livekit.org.webrtc.GlTextureFrameBuffer
import livekit.org.webrtc.GlUtil
import livekit.org.webrtc.RendererCommon


class BackgroundTransformer(
    val blurRadius: Float = 16f,
    val downSampleFactor: Int = 4,
) : RendererCommon.GlDrawer {

    lateinit var compositeShader: CompositeShader
    lateinit var blurShader: BlurShader
    lateinit var boxBlurShader: BlurShader

    var bgTexture = 0
    var frameTexture = 0

    lateinit var bgBlurTextureFrameBuffers: Pair<GlTextureFrameBuffer, GlTextureFrameBuffer>

    lateinit var downSampler: DownSamplerShader

    // For double buffering the final mask
    val readMaskIndex = 0 // Index for renderFrame to read from
    val writeMaskIndex = 1 // Index for updateMask to write to

    lateinit var tempMaskTextureFrameBuffer: GlTextureFrameBuffer

    lateinit var finalMaskFrameBuffers: List<GlTextureFrameBuffer>

    var initialized = false

    fun initialize() {

        LKLog.e { "initialize shaders" }
        compositeShader = createCompsiteShader()
        blurShader = createBlurShader()
        boxBlurShader = createBoxBlurShader()

        bgTexture = GlUtil.generateTexture(GLES20.GL_TEXTURE_2D)
        frameTexture = GlUtil.generateTexture(GLES20.GL_TEXTURE_2D)

        bgBlurTextureFrameBuffers = GlTextureFrameBuffer(GLES20.GL_RGBA) to GlTextureFrameBuffer(GLES20.GL_RGBA)

        downSampler = createDownSampler()

        // For double buffering the final mask
        tempMaskTextureFrameBuffer = GlTextureFrameBuffer(GLES20.GL_RGBA)

        finalMaskFrameBuffers = listOf(GlTextureFrameBuffer(GLES20.GL_RGBA), GlTextureFrameBuffer(GLES20.GL_RGBA))

        initialized = true
    }

    override fun drawOes(
        oesTextureId: Int,
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        LKLog.e { "drawOes" }
        if (!initialized) {
            initialize()
        }

        val downSampleWidth = frameWidth / downSampleFactor
        val downSampleHeight = frameWidth / downSampleFactor
        downSampler.prepare(downSampleWidth, downSampleHeight)

        val downSampledFrameTexture = downSampler.applyDownsampling(frameTexture, downSampleWidth, downSampleHeight, texMatrix)
        val backgroundTexture = blurShader.applyBlur(downSampledFrameTexture, blurRadius, downSampleWidth, downSampleHeight, bgBlurTextureFrameBuffers, texMatrix)

        compositeShader.renderComposite(
            backgroundTextureId = backgroundTexture,
            frameTextureId = oesTextureId,
            maskTextureId = finalMaskFrameBuffers.first().textureId,
            viewportX = viewportX,
            viewportY = viewportY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            texMatrix = texMatrix,
        )
    }


    override fun drawRgb(p0: Int, p1: FloatArray?, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int) {
        TODO("Not yet implemented")
    }

    override fun drawYuv(p0: IntArray?, p1: FloatArray?, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int) {
        TODO("Not yet implemented")
    }

    override fun release() {
        TODO("Not yet implemented")
    }
}
