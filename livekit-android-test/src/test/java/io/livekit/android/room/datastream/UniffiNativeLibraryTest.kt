/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room.datastream

import io.livekit.android.test.BaseTest
import io.livekit.uniffi.buildVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one piece of infrastructure every other data stream test depends on: that the
 * livekit-uniffi native library can be found and loaded from a host JVM test run.
 *
 * When this fails, every data stream test fails with an inscrutable
 * [UnsatisfiedLinkError]/`NoClassDefFoundError` from deep inside JNA. Failing here first, with a
 * pointer to the fix, saves that debugging. See gradle/uniffi-native-lib.gradle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UniffiNativeLibraryTest : BaseTest() {
    @Test
    fun loadsNativeLibrary() {
        // buildVersion() is the cheapest possible FFI call: no arguments, no objects, and it
        // forces the UniffiLib class-init that does Native.register() plus the checksum contract
        // check between these Kotlin bindings and the .dylib/.so they were generated from.
        val version = buildVersion()

        assertTrue(
            "livekit-uniffi reported an empty build version",
            version.isNotEmpty(),
        )
    }
}
