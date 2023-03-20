package io.livekit.android.webrtc

import io.livekit.android.BaseTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.webrtc.PeerConnection.RTCConfiguration

class RTCConfigurationTest : BaseTest() {

    @Test
    fun copyTest() {
        val originalConfig = RTCConfiguration(mutableListOf())
        fillWithMockData(originalConfig)
        val newConfig = originalConfig.copy()
        newConfig::class.java
            .declaredFields
            .forEach { field ->
                assertEquals("Failed on ${field.name}", field.get(originalConfig), field.get(newConfig))
            }
    }

    // Test to make sure the copy test is actually checking properly
    @Test
    fun copyFailureCheckTest() {
        val originalConfig = RTCConfiguration(mutableListOf())
        fillWithMockData(originalConfig)
        val newConfig = originalConfig.copy()

        newConfig.activeResetSrtpParams = false

        var caughtError = false
        try {
            newConfig::class.java
                .declaredFields
                .forEach { field ->
                    assertEquals("Failed on ${field.name}", field.get(originalConfig), field.get(newConfig))
                }
        } catch (e: java.lang.AssertionError) {
            // Error expected
            caughtError = true
        }

        assertTrue(caughtError)
    }

    private fun fillWithMockData(config: RTCConfiguration) {
        config::class.java
            .declaredFields
            .forEach { field ->
                // Ignore iceServers.
                if (field.name == "iceServers") {
                    return@forEach
                }

                val value = field.get(config)
                val newValue = if (value == null) {
                    when (field.type) {
                        Byte::class.javaObjectType -> 1.toByte()
                        Short::class.javaObjectType -> 1.toShort()
                        Int::class.javaObjectType -> 1
                        Long::class.javaObjectType -> 1.toLong()
                        Float::class.javaObjectType -> 1.toFloat()
                        Double::class.javaObjectType -> 1.toDouble()
                        Boolean::class.javaObjectType -> true
                        Char::class.javaObjectType -> 1.toChar()
                        String::class.javaObjectType -> "mock string"
                        else -> Mockito.mock(field.type)
                    }
                } else {
                    when (value::class.javaObjectType) {
                        Byte::class.javaObjectType -> ((value as Byte) + 1).toByte()
                        Short::class.javaObjectType -> ((value as Short) + 1).toShort()
                        Int::class.javaObjectType -> (value as Int) + 1
                        Long::class.javaObjectType -> (value as Long) + 1
                        Float::class.javaObjectType -> (value as Float) + 1
                        Double::class.javaObjectType -> (value as Double) + 1
                        Boolean::class.javaObjectType -> !(value as Boolean)
                        Char::class.javaObjectType -> (value as Char) + 1
                        String::class.javaObjectType -> "mock string"
                        else -> Mockito.mock(field.type)
                    }
                }
                field.set(config, newValue)
            }
    }
}