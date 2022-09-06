package io.livekit.android.assert

import org.junit.Assert

fun assertIsClass(expectedClass: Class<*>, actual: Any) {
    val klazz = actual::class.java

    Assert.assertEquals(expectedClass, klazz)
}

fun assertIsClassList(expectedClasses: List<Class<*>>, actual: List<*>) {
    val klazzes = actual.map {
        if (it == null) {
            Nothing::class.java
        } else {
            it::class.java
        }
    }

    Assert.assertEquals(expectedClasses, klazzes)
}