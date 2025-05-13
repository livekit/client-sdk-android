package io.livekit.android.track.processing.video

import android.opengl.GLES20
import android.opengl.GLES30
import io.livekit.android.track.processing.video.opengl.LKGlTextureFrameBuffer
import io.livekit.android.track.processing.video.shader.BlurShader
import io.livekit.android.track.processing.video.shader.CompositeShader
import io.livekit.android.track.processing.video.shader.ResamplerShader
import io.livekit.android.track.processing.video.shader.createBlurShader
import io.livekit.android.track.processing.video.shader.createBoxBlurShader
import io.livekit.android.track.processing.video.shader.createCompsiteShader
import io.livekit.android.track.processing.video.shader.createResampler
import io.livekit.android.util.LKLog
import livekit.org.webrtc.GlTextureFrameBuffer
import livekit.org.webrtc.GlUtil
import livekit.org.webrtc.RendererCommon
import java.nio.ByteBuffer

/**
 * Blurs the background of the camera video stream.
 */
class VirtualBackgroundTransformer(
    val blurRadius: Float = 16f,
    val downSampleFactor: Int = 2,
) : RendererCommon.GlDrawer {

    data class MaskHolder(val width: Int, val height: Int, val buffer: ByteBuffer)

    private lateinit var compositeShader: CompositeShader
    private lateinit var blurShader: BlurShader
    private lateinit var boxBlurShader: BlurShader

    private var bgTexture = 0
    private var frameTexture = 0

    private lateinit var bgBlurTextureFrameBuffers: Pair<GlTextureFrameBuffer, GlTextureFrameBuffer>

    private lateinit var downSampler: ResamplerShader

    // For double buffering the final mask
    private var readMaskIndex = 0 // Index for renderFrame to read from
    private var writeMaskIndex = 1 // Index for updateMask to write to

    private fun swapMaskIndexes() {
        if (readMaskIndex == 0) {
            readMaskIndex = 1
            writeMaskIndex = 0
        } else {
            readMaskIndex = 0
            writeMaskIndex = 1
        }
    }

    var newMask: MaskHolder? = null
    lateinit var anotherTempMaskFrameBuffer: LKGlTextureFrameBuffer
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

        downSampler = createResampler()

        // For double buffering the final mask
        anotherTempMaskFrameBuffer = LKGlTextureFrameBuffer(GLES30.GL_R32F, GLES30.GL_RED, GLES30.GL_FLOAT)
        tempMaskTextureFrameBuffer = GlTextureFrameBuffer(GLES20.GL_RGBA)

        finalMaskFrameBuffers = listOf(GlTextureFrameBuffer(GLES20.GL_RGBA), GlTextureFrameBuffer(GLES20.GL_RGBA))

        GlUtil.checkNoGLES2Error("VirtualBackgroundTransformer.initialize")
        initialized = true
    }

    companion object {

        val IDENTITY =
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            )

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

        newMask?.let {
            updateMaskFrameBuffer(it)
            newMask = null
        }

        val downSampleWidth = frameWidth / downSampleFactor
        val downSampleHeight = frameHeight / downSampleFactor

        val downSampledFrameTexture = downSampler.resample(oesTextureId, downSampleWidth, downSampleHeight, IDENTITY)
        val backgroundTexture =
            blurShader.applyBlur(downSampledFrameTexture, blurRadius, downSampleWidth, downSampleHeight, bgBlurTextureFrameBuffers)

        compositeShader.renderComposite(
            backgroundTextureId = backgroundTexture,
            frameTextureId = oesTextureId,
            maskTextureId = finalMaskFrameBuffers[readMaskIndex].textureId,
            viewportX = viewportX,
            viewportY = viewportY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            texMatrix = texMatrix,
        )

    }

    /**
     * Thread-safe method to set the foreground mask.
     */
    fun updateMask(segmentationMask: MaskHolder) {
        newMask = segmentationMask
    }

    private fun updateMaskFrameBuffer(segmentationMask: MaskHolder) {

        val width = segmentationMask.width
        val height = segmentationMask.height

        anotherTempMaskFrameBuffer.setSize(segmentationMask.width, segmentationMask.height)
        tempMaskTextureFrameBuffer.setSize(segmentationMask.width, segmentationMask.height)
        finalMaskFrameBuffers[0].setSize(segmentationMask.width, segmentationMask.height)
        finalMaskFrameBuffers[1].setSize(segmentationMask.width, segmentationMask.height)

        // Upload the mask into a texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, anotherTempMaskFrameBuffer.textureId)
        GlUtil.checkNoGLES2Error("BackgroundTransformer.glBindTexture")

        GLES20.glTexSubImage2D(
            /*target*/ GLES20.GL_TEXTURE_2D,
            0,
            0,
            0,
            width,
            height,
            /*format*/GLES30.GL_RED,
            /*type*/GLES20.GL_FLOAT,
            segmentationMask.buffer,
        )

        GlUtil.checkNoGLES2Error("BackgroundTransformer.updateMaskFrameBuffer")

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val finalMaskBuffer = finalMaskFrameBuffers[writeMaskIndex]
        val frameBuffers = tempMaskTextureFrameBuffer to finalMaskBuffer

        boxBlurShader.applyBlur(anotherTempMaskFrameBuffer.textureId, 2f, width, height, frameBuffers)

        // Swap indicies for next frame.
        swapMaskIndexes()
    }

    override fun drawRgb(p0: Int, p1: FloatArray?, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int) {
        TODO("Not yet implemented")
    }

    override fun drawYuv(p0: IntArray?, p1: FloatArray?, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int) {
        TODO("Not yet implemented")
    }

    override fun release() {

        compositeShader.release()
        blurShader.release()
        boxBlurShader.release()

        bgBlurTextureFrameBuffers.first.release()
        bgBlurTextureFrameBuffers.second.release()
        downSampler.release()

        anotherTempMaskFrameBuffer.release()
        tempMaskTextureFrameBuffer.release()
        finalMaskFrameBuffers.forEach {
            it.release()
        }

    }
}

