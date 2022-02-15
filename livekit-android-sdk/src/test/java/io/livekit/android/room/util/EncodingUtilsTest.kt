package io.livekit.android.room.util

import org.junit.Assert
import org.junit.Test

class EncodingUtilsTest {
    @Test
    fun evenScale() {
        fun Int.isEven() = this % 2 == 0

        val sourceWidth = 800
        val sourceHeight = 600
        val scaleDownBy = EncodingUtils.findEvenScaleDownBy(sourceWidth, sourceHeight, 240, 180)
            ?: throw Exception("scale should not be null!")

        Assert.assertTrue((sourceWidth / scaleDownBy).toInt().isEven())
        Assert.assertTrue((sourceHeight / scaleDownBy).toInt().isEven())
    }

    @Test
    fun evenScaleWeirdSource() {
        fun Int.isEven() = this % 2 == 0

        val sourceWidth = 800
        val sourceHeight = 602
        val scaleDownBy = EncodingUtils.findEvenScaleDownBy(sourceWidth, sourceHeight, 240, 180)
            ?: throw Exception("scale should not be null!")

        Assert.assertTrue((sourceWidth / scaleDownBy).toInt().isEven())
        Assert.assertTrue((sourceHeight / scaleDownBy).toInt().isEven())
    }
}