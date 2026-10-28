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

package io.livekit.android.telemetry

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.util.LKLog
import io.livekit.uniffi.telemetrySetDeviceState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import livekit.org.webrtc.CameraVideoCapturer
import uniffi.livekit_telemetry.AppState
import uniffi.livekit_telemetry.AudioOutput
import uniffi.livekit_telemetry.AudioRouteReason
import uniffi.livekit_telemetry.CaptureDevice
import uniffi.livekit_telemetry.CaptureFailure
import uniffi.livekit_telemetry.DeviceEvent
import uniffi.livekit_telemetry.DeviceState
import uniffi.livekit_telemetry.MemoryPressure
import uniffi.livekit_telemetry.NetworkType
import uniffi.livekit_telemetry.TelemetryInstrument
import uniffi.livekit_telemetry.ThermalState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The device instrument: thermal status, battery saver, memory pressure, network, battery and app
 * lifecycle as [DeviceState]. Every OS callback sends its change into one channel, drained in
 * order by one coroutine on this instrument's own serial dispatcher; the core turns the state into
 * `lk.device.*` records and upload holds. Nothing polls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DeviceTelemetry(context: Context) : TelemetryInstrument {
    private val app = context.applicationContext
    private val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Suppress("InjectDispatcher") // the instrument's own serial dispatcher, outside any Room
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + CoroutineExceptionHandler { _, e -> LKLog.w(e) { "Device telemetry stopped." } },
    )

    // ponytail: unbounded, OS callbacks are a handful per minute at most
    private val changes = Channel<DeviceState.() -> Unit>(Channel.UNLIMITED)
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private fun post(change: DeviceState.() -> Unit = {}) {
        changes.trySend(change)
    }

    // MARK: - Lifecycle

    override fun start() {
        scope.launch {
            val state = DeviceState(
                thermal = ThermalState.UNKNOWN,
                lowPowerMode = null,
                appState = AppState.FOREGROUND,
                memory = MemoryPressure.NORMAL,
                network = NetworkType.UNKNOWN,
            )
            for (change in changes) {
                // One failing change must not end the stream.
                runCatching {
                    state.change()
                    // Read fresh on every change: these have no payload of their own.
                    state.thermal = thermal()
                    state.lowPowerMode = power?.isPowerSaveMode // unknown without a power service
                    state.networkConstrained = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
                    telemetrySetDeviceState(state.copy())
                }.onFailure { e -> LKLog.w(e) { "Device telemetry skipped a change." } }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) addAction(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
        }
        // System broadcasts only; the return value is the sticky battery intent: the first push.
        val sticky = ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        post { sticky?.let { battery(it) } }
        // ponytail: no default-network callback below API 24, so the network stays unknown there
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivity?.registerDefaultNetworkCallback(networkCallback) // delivers the current network at once
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { post() }.also { power?.addThermalStatusListener(it) }
        }
        app.registerComponentCallbacks(memoryCallbacks)
        (app as? Application)?.registerActivityLifecycleCallbacks(activityCallbacks)
    }

    /**
     * Called by the core on the caller's thread (the opt-out's, often main) under its lifecycle
     * lock: only unregisters and cancels, never waits, never calls back into telemetry, never throws.
     */
    override fun stop() {
        runCatching { app.unregisterReceiver(receiver) }
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { thermalListener?.let { power?.removeThermalStatusListener(it) } }
        runCatching { app.unregisterComponentCallbacks(memoryCallbacks) }
        runCatching { (app as? Application)?.unregisterActivityLifecycleCallbacks(activityCallbacks) }
        scope.cancel()
    }

    // MARK: - Power, battery, Data Saver

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            post { if (intent.action == Intent.ACTION_BATTERY_CHANGED) battery(intent) }
        }
    }

    private fun DeviceState.battery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale).toUInt() else null
        batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun thermal(): ThermalState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.UNKNOWN // no thermal API
        return when (power?.currentThermalStatus ?: return ThermalState.UNKNOWN) {
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
            PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
            else -> ThermalState.NOMINAL // NONE, LIGHT
        }
    }

    // MARK: - Network

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = post {
            this.network = capabilities.type
            networkExpensive = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        override fun onLost(network: Network) = post {
            this.network = NetworkType.UNAVAILABLE
            networkExpensive = false
        }
    }

    private val NetworkCapabilities.type: NetworkType
        get() = when {
            hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELL
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.WIRED
            hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkType.BLUETOOTH
            else -> NetworkType.OTHER
        }

    // MARK: - Memory and app state

    /**
     * The trim levels the OS sends; hiding every activity is the move to the background. The OS
     * never says pressure is over: it counts as normal again when an activity starts.
     */
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> post { appState = AppState.BACKGROUND }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> post { memory = MemoryPressure.CRITICAL }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            -> post { memory = MemoryPressure.WARNING }

            else -> {}
        }

        override fun onLowMemory() = post { memory = MemoryPressure.CRITICAL }

        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = post {
            appState = AppState.FOREGROUND
            memory = MemoryPressure.NORMAL
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

        override fun onActivityResumed(activity: Activity) {}

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {}
    }
}

// MARK: - Device events

/**
 * Audio route changes and focus loss: events, not state, they explain audio glitches. Process
 * events, so one listener pair per handler however many Rooms share it (an app-supplied handler
 * can be); the last Room to release it removes them. The audio switch names no reason for a
 * change; a focus loss is an interruption that ends on the matching gain. Returns this Room's
 * release, which is idempotent.
 */
internal fun AudioSwitchHandler.observeForTelemetry(): () -> Unit {
    val observers = synchronized(audioObservers) { audioObservers.getOrPut(this) { AudioObservers() }.also { it.rooms++ } }
    reconcile(observers)
    val released = AtomicBoolean(false)
    return {
        if (released.compareAndSet(false, true)) {
            synchronized(audioObservers) { observers.rooms-- }
            reconcile(observers)
        }
    }
}

/**
 * Registers or unregisters [observers] until that matches whether any Room uses them. The
 * handler is only called outside the registry lock: it dispatches under its own listener locks,
 * and a callback may create or release a Room. One thread acts at a time; a thread that finds
 * another acting leaves it to re-check when done.
 */
private fun AudioSwitchHandler.reconcile(observers: AudioObservers) {
    while (true) {
        val register = synchronized(audioObservers) {
            val wanted = observers.rooms > 0
            if (observers.busy) return
            if (observers.registered == wanted) {
                if (!wanted && audioObservers[this] === observers) audioObservers.remove(this)
                return
            }
            observers.busy = true
            wanted
        }
        try {
            if (register) {
                registerAudioDeviceChangeListener(observers.route)
                registerOnAudioFocusChangeListener(observers.focus)
            } else {
                unregisterAudioDeviceChangeListener(observers.route)
                unregisterOnAudioFocusChangeListener(observers.focus)
            }
        } finally {
            synchronized(audioObservers) {
                observers.registered = register
                observers.busy = false
            }
        }
    }
}

/** The listener pair of one handler and how many Rooms use it; holds no handler or Room. Guarded by the registry. */
private class AudioObservers {
    var rooms = 0
    var registered = false
    var busy = false
    val route = object : AudioDeviceChangeListener {
        override fun invoke(devices: List<AudioDevice>, selected: AudioDevice?) =
            Telemetry.deviceEvent(DeviceEvent.AudioRouteChanged(listOfNotNull(selected?.output), AudioRouteReason.UNKNOWN))
    }
    val focus = AudioManager.OnAudioFocusChangeListener { change -> Telemetry.deviceEvent(DeviceEvent.AudioInterruption(began = change < 0)) }
}

// ponytail: strong keys; an entry lives exactly as long as some Room still holds its handler
private val audioObservers = HashMap<AudioSwitchHandler, AudioObservers>()

private val AudioDevice.output: AudioOutput
    get() = when (this) {
        is AudioDevice.BluetoothHeadset -> AudioOutput.BLUETOOTH
        is AudioDevice.WiredHeadset -> AudioOutput.WIRED_HEADSET
        is AudioDevice.Earpiece -> AudioOutput.RECEIVER
        is AudioDevice.Speakerphone -> AudioOutput.SPEAKER
        else -> AudioOutput.OTHER
    }

/** Camera failures from WebRTC's capturer, on every camera track the SDK opens. */
internal object TelemetryCameraEvents : CameraVideoCapturer.CameraEventsHandler {
    override fun onCameraError(message: String?) =
        Telemetry.deviceEvent(DeviceEvent.CaptureFailed(CaptureDevice.CAMERA, CaptureFailure.OTHER))

    override fun onCameraDisconnected() =
        Telemetry.deviceEvent(DeviceEvent.CaptureFailed(CaptureDevice.CAMERA, CaptureFailure.DISCONNECTED))

    override fun onCameraFreezed(message: String?) {}

    override fun onCameraOpening(cameraName: String?) {}

    override fun onFirstFrameAvailable() {}

    override fun onCameraClosed() {}
}

/** A microphone that failed to start, from WebRTC's audio device module. */
internal fun telemetryMicrophoneFailed() =
    Telemetry.deviceEvent(DeviceEvent.CaptureFailed(CaptureDevice.MICROPHONE, CaptureFailure.OTHER))
