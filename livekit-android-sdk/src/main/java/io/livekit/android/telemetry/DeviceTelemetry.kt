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

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.uniffi.telemetrySetDeviceState
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

/**
 * The Device-area instrument: thermal status, battery saver, memory pressure, network (type,
 * metered, Data Saver) and battery, observed process-wide (a device has no room) and pushed to
 * the pipeline as [DeviceState] — which stretches the cadence and holds uploads. Callback-driven
 * throughout: nothing polls. App state stays `foreground`: the SDK has no lifecycle dependency.
 */
internal class DeviceTelemetry(context: Context) : TelemetryInstrument {
    private val app = context.applicationContext
    private val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var memory = MemoryPressure.NORMAL

    @Volatile
    private var network: NetworkCapabilities? = null

    @Volatile
    private var battery: Intent? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) battery = intent
            push()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            this@DeviceTelemetry.network = capabilities
            push()
        }

        override fun onLost(network: Network) {
            this@DeviceTelemetry.network = null
            push()
        }
    }

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            memory = when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressure.CRITICAL
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> return // the UI went away, not memory pressure
                else -> MemoryPressure.WARNING
            }
            push()
        }

        override fun onLowMemory() {
            memory = MemoryPressure.CRITICAL
            push()
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    override fun start() {
        network = connectivityManager?.let { cm ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.activeNetwork?.let(cm::getNetworkCapabilities) else null
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) addAction(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
        }
        // System broadcasts only; the return value is the sticky battery intent.
        battery = ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        connectivityManager?.let { cm ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallback)
            } else {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    networkCallback,
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { push() }.also { powerManager?.addThermalStatusListener(it) }
        }
        app.registerComponentCallbacks(memoryCallbacks)
        push()
    }

    override fun stop() {
        runCatching { app.unregisterReceiver(receiver) }
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalListener?.let { powerManager?.removeThermalStatusListener(it) }
        app.unregisterComponentCallbacks(memoryCallbacks)
    }

    private fun push() {
        val battery = battery
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        telemetrySetDeviceState(
            DeviceState(
                thermal = thermal(),
                lowPowerMode = powerManager?.isPowerSaveMode == true,
                appState = AppState.FOREGROUND,
                memory = memory,
                network = network.type,
                networkExpensive = network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
                networkConstrained = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    connectivityManager?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                batteryLevel = if (level != null && scale != null) (level * 100 / scale).toUInt() else null,
                batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            ),
        )
    }

    private fun thermal(): ThermalState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.NOMINAL
        return when (powerManager?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
            PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
            else -> ThermalState.NOMINAL // NONE, LIGHT
        }
    }

    private val NetworkCapabilities?.type: NetworkType
        get() = when {
            this == null -> NetworkType.UNAVAILABLE
            hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELL
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.WIRED
            hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkType.BLUETOOTH
            else -> NetworkType.OTHER
        }

    companion object {
        /**
         * Route changes and focus loss are events, not state: they explain audio glitches.
         * AudioSwitch names no reason for a change; a focus loss is an interruption that ends on
         * the matching gain.
         */
        fun observe(audio: AudioSwitchHandler) {
            audio.registerAudioDeviceChangeListener { _, selected ->
                Telemetry.deviceEvent(DeviceEvent.AudioRouteChanged(listOfNotNull(selected?.output), AudioRouteReason.UNKNOWN))
            }
            audio.registerOnAudioFocusChangeListener { change ->
                Telemetry.deviceEvent(DeviceEvent.AudioInterruption(began = change < 0))
            }
        }

        private val AudioDevice.output: AudioOutput
            get() = when (this) {
                is AudioDevice.BluetoothHeadset -> AudioOutput.BLUETOOTH
                is AudioDevice.WiredHeadset -> AudioOutput.WIRED_HEADSET
                is AudioDevice.Earpiece -> AudioOutput.RECEIVER
                is AudioDevice.Speakerphone -> AudioOutput.SPEAKER
                else -> AudioOutput.OTHER
            }
    }
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
