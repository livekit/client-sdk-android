/*
 * Copyright 2024-2026 LiveKit, Inc.
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

package io.livekit.android.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.CommDeviceAudioSwitch
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class AudioSwitchHandlerTest {

    private lateinit var context: Context
    private lateinit var audioSwitchHandler: AudioSwitchHandler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioSwitchHandler = AudioSwitchHandler(context)
    }

    private fun idleAllLoopers() {
        ShadowLooper.getAllLoopers()
            .filter { it != Looper.getMainLooper() }
            .forEach {
                try {
                    shadowOf(it).idle()
                } catch (_: IllegalStateException) {
                }
            }
    }

    /**
     * Creates a CommDeviceAudioSwitch whose internal state is STARTED but whose
     * CommunicationDeviceScanner listener was never registered with AudioManager.
     * Calling stop() on this instance triggers the production crash.
     */
    private fun createUnstartedSwitch(): CommDeviceAudioSwitch {
        val switch = CommDeviceAudioSwitch(
            context = context,
            loggingEnabled = false,
            audioFocusChangeListener = {},
            preferredDeviceList = listOf(
                AudioDevice.Speakerphone::class.java,
                AudioDevice.Earpiece::class.java,
            ),
        )
        val abstractClass = Class.forName("com.twilio.audioswitch.AbstractAudioSwitch")
        val stateField = abstractClass.getDeclaredField("state")
        stateField.isAccessible = true
        val stateClass = Class.forName("com.twilio.audioswitch.AbstractAudioSwitch\$State")
        stateField.set(switch, stateClass.getDeclaredField("STARTED").get(null))
        return switch
    }

    /**
     * Injects the race condition state into AudioSwitchHandler via reflection,
     * simulating what happens when stop() cancels a pending start() task:
     * - audioSwitch is non-null (created but scanner listener never registered)
     * - isAudioSwitchStarted is NOT touched — relies on the production code's
     *   value (should be false if start task never executed)
     * - handler points to main looper (so we can idle it in tests)
     * - thread is null (so quitSafely() is a no-op, allowing the posted task
     *   to be drained via ShadowLooper.idleMainLooper())
     */
    private fun injectRaceConditionState(unstartedSwitch: CommDeviceAudioSwitch) {
        val ashClass = AudioSwitchHandler::class.java
        ashClass.getDeclaredField("audioSwitch").apply {
            isAccessible = true
            set(audioSwitchHandler, unstartedSwitch)
        }
        ashClass.getDeclaredField("handler").apply {
            isAccessible = true
            set(audioSwitchHandler, Handler(Looper.getMainLooper()))
        }
        ashClass.getDeclaredField("thread").apply {
            isAccessible = true
            set(audioSwitchHandler, null)
        }
    }

    // ---- Core crash reproduction tests ----

    /**
     * Proves the crash exists at the AudioSwitch level: calling stop() on a
     * CommDeviceAudioSwitch whose internal state is STARTED but whose
     * CommunicationDeviceScanner listener was never registered with AudioManager
     * throws the exact IllegalArgumentException from the production crash.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `CommDeviceAudioSwitch stop without start throws IllegalArgumentException`() {
        createUnstartedSwitch().stop()
    }

    /**
     * Verifies the normal lifecycle: a properly started CommDeviceAudioSwitch
     * can be stopped without crash.
     */
    @Test
    fun `CommDeviceAudioSwitch start then stop does not crash`() {
        val switch = CommDeviceAudioSwitch(
            context = context,
            loggingEnabled = false,
            audioFocusChangeListener = {},
            preferredDeviceList = listOf(
                AudioDevice.Speakerphone::class.java,
                AudioDevice.Earpiece::class.java,
            ),
        )

        switch.start { _, _ -> }
        shadowOf(Looper.getMainLooper()).idle()
        switch.stop()
    }

    // ---- Guard validation tests ----

    /**
     * Reproduces the exact production race condition and verifies that the
     * isAudioSwitchStarted guard in AudioSwitchHandler.stop() prevents the crash.
     *
     * Production scenario:
     * 1. start() posts a task to create + start AudioSwitch on handler thread
     * 2. stop() cancels the pending start via removeCallbacksAndMessages(null)
     * 3. stop() posts audioSwitch.stop() to handler thread
     * 4. Handler thread executes the stop task — audioSwitch exists (non-null,
     *    state=STARTED) but was never actually started, so its scanner listener
     *    was never registered with AudioManager
     * 5. audioSwitch.stop() → closeListeners() → CommunicationDeviceScanner.stop()
     *    → removeOnCommunicationDeviceChangedListener on unregistered listener → crash
     *
     * We simulate this by injecting the race condition state, then calling
     * the real AudioSwitchHandler.stop(). The handler is set to use the main
     * looper (so we can drain the posted task) and thread is null (so
     * quitSafely() is a no-op). This lets the real stop() lambda execute via
     * ShadowLooper.idleMainLooper().
     *
     * When isAudioSwitchStarted is false (correct fix), the guard skips
     * audioSwitch.stop() and no crash occurs.
     * When isAudioSwitchStarted is true (guard disabled), audioSwitch.stop()
     * is called and throws IllegalArgumentException.
     */
    @Test
    fun `stop with unstarted audioSwitch does not crash when guard is active`() {
        injectRaceConditionState(createUnstartedSwitch())

        // Call the real stop() — posts the stop lambda to main looper.
        audioSwitchHandler.stop()

        // Drain the main looper to execute the posted stop task.
        // Without the guard, this throws IllegalArgumentException.
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- AudioSwitchHandler lifecycle tests ----

    @Test
    fun `stop immediately after start should not crash`() {
        audioSwitchHandler.start()
        audioSwitchHandler.stop()
        idleAllLoopers()
    }

    @Test
    fun `rapid start-stop cycles should not crash`() {
        repeat(10) {
            audioSwitchHandler.start()
            audioSwitchHandler.stop()
        }
        idleAllLoopers()
    }

    @Test
    fun `stop without start should not crash`() {
        audioSwitchHandler.stop()
        idleAllLoopers()
    }

    @Test
    fun `double stop should not crash`() {
        audioSwitchHandler.start()
        idleAllLoopers()

        audioSwitchHandler.stop()
        idleAllLoopers()

        audioSwitchHandler.stop()
        idleAllLoopers()
    }

    @Test
    fun `full start-stop-start-stop lifecycle`() {
        audioSwitchHandler.start()
        idleAllLoopers()

        audioSwitchHandler.stop()
        idleAllLoopers()

        audioSwitchHandler.start()
        idleAllLoopers()

        audioSwitchHandler.stop()
        idleAllLoopers()
    }

    @Test
    fun `audioSwitch is null after stop`() {
        audioSwitchHandler.start()
        idleAllLoopers()

        audioSwitchHandler.stop()
        idleAllLoopers()

        assertNull(audioSwitchHandler.selectedAudioDevice)
    }
}
