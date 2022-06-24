/*
 *  Copyright 2016 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package io.livekit.android.sample.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.webrtc.ThreadUtils
import timber.log.Timber

/**
 * AppRTCProximitySensor manages functions related to Bluetoth devices in the
 * AppRTC demo.
 */
@Suppress("MemberVisibilityCanBePrivate")
@SuppressLint("BinaryOperationInTimber")
open class AppRTCBluetoothManager
constructor(context: Context, audioManager: AppRTCAudioManager) {
    // Bluetooth connection state.
    enum class State {
        // Bluetooth is not available; no adapter or Bluetooth is off.
        UNINITIALIZED,  // Bluetooth error happened when trying to start Bluetooth.
        ERROR,  // Bluetooth proxy object for the Headset profile exists, but no connected headset devices,

        // SCO is not started or disconnected.
        HEADSET_UNAVAILABLE,  // Bluetooth proxy object for the Headset profile connected, connected Bluetooth headset

        // present, but SCO is not started or disconnected.
        HEADSET_AVAILABLE,  // Bluetooth audio SCO connection with remote device is closing.
        SCO_DISCONNECTING,  // Bluetooth audio SCO connection with remote device is initiated.
        SCO_CONNECTING,  // Bluetooth audio SCO connection with remote device is established.
        SCO_CONNECTED
    }

    private val apprtcContext: Context
    private val apprtcAudioManager: AppRTCAudioManager
    private val audioManager: AudioManager?
    private val handler: Handler
    var scoConnectionAttempts = 0
    private var bluetoothState: State
    private val bluetoothServiceListener: BluetoothProfile.ServiceListener
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private val bluetoothHeadsetReceiver: BroadcastReceiver

    // Runs when the Bluetooth timeout expires. We use that timeout after calling
    // startScoAudio() or stopScoAudio() because we're not guaranteed to get a
    // callback after those calls.
    private val bluetoothTimeoutRunnable = Runnable { bluetoothTimeout() }

    /**
     * Implementation of an interface that notifies BluetoothProfile IPC clients when they have been
     * connected to or disconnected from the service.
     */
    private inner class BluetoothServiceListener : BluetoothProfile.ServiceListener {
        // Called to notify the client when the proxy object has been connected to the service.
        // Once we have the profile proxy object, we can use it to monitor the state of the
        // connection and perform other operations that are relevant to the headset profile.
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HEADSET || bluetoothState == State.UNINITIALIZED) {
                return
            }
            Timber.d("BluetoothServiceListener.onServiceConnected: BT state=$bluetoothState")
            // Android only supports one connected Bluetooth Headset at a time.
            bluetoothHeadset = proxy as BluetoothHeadset
            updateAudioDeviceState()
            Timber.d("onServiceConnected done: BT state=$bluetoothState")
        }

        /** Notifies the client when the proxy object has been disconnected from the service.  */
        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HEADSET || bluetoothState == State.UNINITIALIZED) {
                return
            }
            Timber.d("BluetoothServiceListener.onServiceDisconnected: BT state=$bluetoothState")
            stopScoAudio()
            bluetoothHeadset = null
            bluetoothDevice = null
            bluetoothState = State.HEADSET_UNAVAILABLE
            updateAudioDeviceState()
            Timber.d("onServiceDisconnected done: BT state=$bluetoothState")
        }
    }

    // Intent broadcast receiver which handles changes in Bluetooth device availability.
    // Detects headset changes and Bluetooth SCO state changes.
    private inner class BluetoothHeadsetBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (bluetoothState == State.UNINITIALIZED) {
                return
            }
            val action = intent.action
            // Change in connection state of the Headset profile. Note that the
            // change does not tell us anything about whether we're streaming
            // audio to BT over SCO. Typically received when user turns on a BT
            // headset while audio is active using another audio device.
            if (action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
                Timber.d(
                    """BluetoothHeadsetBroadcastReceiver.onReceive: a=ACTION_CONNECTION_STATE_CHANGED, s=${
                        stateToString(
                            state
                        )
                    }, sb=$isInitialStickyBroadcast, BT state: $bluetoothState"""
                )
                if (state == BluetoothHeadset.STATE_CONNECTED) {
                    scoConnectionAttempts = 0
                    updateAudioDeviceState()
                } else if (state == BluetoothHeadset.STATE_CONNECTING) {
                    // No action needed.
                } else if (state == BluetoothHeadset.STATE_DISCONNECTING) {
                    // No action needed.
                } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                    // Bluetooth is probably powered off during the call.
                    stopScoAudio()
                    updateAudioDeviceState()
                }
                // Change in the audio (SCO) connection state of the Headset profile.
                // Typically received after call to startScoAudio() has finalized.
            } else if (action == BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED
                )
                Timber.d(
                    "BluetoothHeadsetBroadcastReceiver.onReceive: "
                            + "a=ACTION_AUDIO_STATE_CHANGED, "
                            + "s=" + stateToString(state) + ", "
                            + "sb=" + isInitialStickyBroadcast + ", "
                            + "BT state: " + bluetoothState
                )
                if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                    cancelTimer()
                    if (bluetoothState == State.SCO_CONNECTING) {
                        Timber.d("+++ Bluetooth audio SCO is now connected")
                        bluetoothState = State.SCO_CONNECTED
                        scoConnectionAttempts = 0
                        updateAudioDeviceState()
                    } else {
                        Timber.w("Unexpected state BluetoothHeadset.STATE_AUDIO_CONNECTED")
                    }
                } else if (state == BluetoothHeadset.STATE_AUDIO_CONNECTING) {
                    Timber.d("+++ Bluetooth audio SCO is now connecting...")
                } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    Timber.d("+++ Bluetooth audio SCO is now disconnected")
                    if (isInitialStickyBroadcast) {
                        Timber.d("Ignore STATE_AUDIO_DISCONNECTED initial sticky broadcast.")
                        return
                    }
                    updateAudioDeviceState()
                }
            }
            Timber.d("onReceive done: BT state=$bluetoothState")
        }
    }

    /** Returns the internal state.  */
    val state: State
        get() {
            ThreadUtils.checkIsOnMainThread()
            return bluetoothState
        }

    /**
     * Activates components required to detect Bluetooth devices and to enable
     * BT SCO (audio is routed via BT SCO) for the headset profile. The end
     * state will be HEADSET_UNAVAILABLE but a state machine has started which
     * will start a state change sequence where the final outcome depends on
     * if/when the BT headset is enabled.
     * Example of state change sequence when start() is called while BT device
     * is connected and enabled:
     * UNINITIALIZED --> HEADSET_UNAVAILABLE --> HEADSET_AVAILABLE -->
     * SCO_CONNECTING --> SCO_CONNECTED <==> audio is now routed via BT SCO.
     * Note that the AppRTCAudioManager is also involved in driving this state
     * change.
     */
    fun start() {
        ThreadUtils.checkIsOnMainThread()
        Timber.d("start")
        if (!hasPermission(apprtcContext, Manifest.permission.BLUETOOTH) &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    hasPermission(apprtcContext, Manifest.permission.BLUETOOTH_CONNECT))
        ) {
            Timber.w("Process (pid=" + Process.myPid() + ") lacks BLUETOOTH permission")
            return
        }
        if (bluetoothState != State.UNINITIALIZED) {
            Timber.w("Invalid BT state")
            return
        }
        bluetoothHeadset = null
        bluetoothDevice = null
        scoConnectionAttempts = 0
        // Get a handle to the default local Bluetooth adapter.
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Timber.w("Device does not support Bluetooth")
            return
        }
        // Ensure that the device supports use of BT SCO audio for off call use cases.
        if (!audioManager!!.isBluetoothScoAvailableOffCall) {
            Timber.e("Bluetooth SCO audio is not available off call")
            return
        }
        logBluetoothAdapterInfo(bluetoothAdapter!!)
        // Establish a connection to the HEADSET profile (includes both Bluetooth Headset and
        // Hands-Free) proxy object and install a listener.
        if (!getBluetoothProfileProxy(
                apprtcContext, bluetoothServiceListener, BluetoothProfile.HEADSET
            )
        ) {
            Timber.e("BluetoothAdapter.getProfileProxy(HEADSET) failed")
            return
        }
        // Register receivers for BluetoothHeadset change notifications.
        val bluetoothHeadsetFilter = IntentFilter()
        // Register receiver for change in connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        // Register receiver for change in audio connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        registerReceiver(bluetoothHeadsetReceiver, bluetoothHeadsetFilter)
        Timber.d(
            "HEADSET profile state: "
                    + stateToString(bluetoothAdapter!!.getProfileConnectionState(BluetoothProfile.HEADSET))
        )
        Timber.d("Bluetooth proxy for headset profile has started")
        bluetoothState = State.HEADSET_UNAVAILABLE
        Timber.d("start done: BT state=$bluetoothState")
    }

    /** Stops and closes all components related to Bluetooth audio.  */
    fun stop() {
        ThreadUtils.checkIsOnMainThread()
        Timber.d("stop: BT state=$bluetoothState")
        if (bluetoothAdapter == null) {
            return
        }
        // Stop BT SCO connection with remote device if needed.
        stopScoAudio()
        // Close down remaining BT resources.
        if (bluetoothState == State.UNINITIALIZED) {
            return
        }
        unregisterReceiver(bluetoothHeadsetReceiver)
        cancelTimer()
        if (bluetoothHeadset != null) {
            bluetoothAdapter!!.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
            bluetoothHeadset = null
        }
        bluetoothAdapter = null
        bluetoothDevice = null
        bluetoothState = State.UNINITIALIZED
        Timber.d("stop done: BT state=$bluetoothState")
    }

    /**
     * Starts Bluetooth SCO connection with remote device.
     * Note that the phone application always has the priority on the usage of the SCO connection
     * for telephony. If this method is called while the phone is in call it will be ignored.
     * Similarly, if a call is received or sent while an application is using the SCO connection,
     * the connection will be lost for the application and NOT returned automatically when the call
     * ends. Also note that: up to and including API version JELLY_BEAN_MR1, this method initiates a
     * virtual voice call to the Bluetooth headset. After API version JELLY_BEAN_MR2 only a raw SCO
     * audio connection is established.
     * TODO(henrika): should we add support for virtual voice call to BT headset also for JBMR2 and
     * higher. It might be required to initiates a virtual voice call since many devices do not
     * accept SCO audio without a "call".
     */
    fun startScoAudio(): Boolean {
        ThreadUtils.checkIsOnMainThread()
        Timber.d(
            "startSco: BT state=" + bluetoothState + ", "
                    + "attempts: " + scoConnectionAttempts + ", "
                    + "SCO is on: " + isScoOn
        )
        if (scoConnectionAttempts >= MAX_SCO_CONNECTION_ATTEMPTS) {
            Timber.e("BT SCO connection fails - no more attempts")
            return false
        }
        if (bluetoothState != State.HEADSET_AVAILABLE) {
            Timber.e("BT SCO connection fails - no headset available")
            return false
        }
        // Start BT SCO channel and wait for ACTION_AUDIO_STATE_CHANGED.
        Timber.d("Starting Bluetooth SCO and waits for ACTION_AUDIO_STATE_CHANGED...")
        // The SCO connection establishment can take several seconds, hence we cannot rely on the
        // connection to be available when the method returns but instead register to receive the
        // intent ACTION_SCO_AUDIO_STATE_UPDATED and wait for the state to be SCO_AUDIO_STATE_CONNECTED.
        bluetoothState = State.SCO_CONNECTING
        audioManager!!.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        scoConnectionAttempts++
        startTimer()
        Timber.d(
            "startScoAudio done: BT state=" + bluetoothState + ", "
                    + "SCO is on: " + isScoOn
        )
        return true
    }

    /** Stops Bluetooth SCO connection with remote device.  */
    fun stopScoAudio() {
        ThreadUtils.checkIsOnMainThread()
        Timber.d(
            "stopScoAudio: BT state=" + bluetoothState + ", "
                    + "SCO is on: " + isScoOn
        )
        if (bluetoothState != State.SCO_CONNECTING && bluetoothState != State.SCO_CONNECTED) {
            return
        }
        cancelTimer()
        audioManager!!.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        bluetoothState = State.SCO_DISCONNECTING
        Timber.d(
            "stopScoAudio done: BT state=" + bluetoothState + ", "
                    + "SCO is on: " + isScoOn
        )
    }

    /**
     * Use the BluetoothHeadset proxy object (controls the Bluetooth Headset
     * Service via IPC) to update the list of connected devices for the HEADSET
     * profile. The internal state will change to HEADSET_UNAVAILABLE or to
     * HEADSET_AVAILABLE and |bluetoothDevice| will be mapped to the connected
     * device if available.
     */
    fun updateDevice() {
        if (bluetoothState == State.UNINITIALIZED || bluetoothHeadset == null) {
            return
        }
        Timber.d("updateDevice")
        // Get connected devices for the headset profile. Returns the set of
        // devices which are in state STATE_CONNECTED. The BluetoothDevice class
        // is just a thin wrapper for a Bluetooth hardware address.
        val devices = bluetoothHeadset!!.connectedDevices
        if (devices.isEmpty()) {
            bluetoothDevice = null
            bluetoothState = State.HEADSET_UNAVAILABLE
            Timber.d("No connected bluetooth headset")
        } else {
            // Always use first device in list. Android only supports one device.
            bluetoothDevice = devices[0]
            bluetoothState = State.HEADSET_AVAILABLE
            Timber.d(
                "Connected bluetooth headset: "
                        + "name=" + bluetoothDevice!!.name + ", "
                        + "state=" + stateToString(bluetoothHeadset!!.getConnectionState(bluetoothDevice))
                        + ", SCO audio=" + bluetoothHeadset!!.isAudioConnected(bluetoothDevice)
            )
        }
        Timber.d("updateDevice done: BT state=$bluetoothState")
    }

    /**
     * Stubs for test mocks.
     */
    protected fun getAudioManager(context: Context): AudioManager? {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    protected fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?) {
        apprtcContext.registerReceiver(receiver, filter)
    }

    protected fun unregisterReceiver(receiver: BroadcastReceiver?) {
        apprtcContext.unregisterReceiver(receiver)
    }

    protected fun getBluetoothProfileProxy(
        context: Context?, listener: BluetoothProfile.ServiceListener?, profile: Int
    ): Boolean {
        return bluetoothAdapter!!.getProfileProxy(context, listener, profile)
    }

    protected fun hasPermission(context: Context?, permission: String?): Boolean {
        return (apprtcContext.checkPermission(permission!!, Process.myPid(), Process.myUid())
                == PackageManager.PERMISSION_GRANTED)
    }

    /** Logs the state of the local Bluetooth adapter.  */
    @SuppressLint("HardwareIds")
    protected fun logBluetoothAdapterInfo(localAdapter: BluetoothAdapter) {
        Timber.d(
            "BluetoothAdapter: "
                    + "enabled=" + localAdapter.isEnabled + ", "
                    + "state=" + stateToString(localAdapter.state) + ", "
                    + "name=" + localAdapter.name + ", "
                    + "address=" + localAdapter.address
        )
        // Log the set of BluetoothDevice objects that are bonded (paired) to the local adapter.
        val pairedDevices = localAdapter.bondedDevices
        if (!pairedDevices.isEmpty()) {
            Timber.d("paired devices:")
            for (device in pairedDevices) {
                Timber.d(" name=" + device.name + ", address=" + device.address)
            }
        }
    }

    /** Ensures that the audio manager updates its list of available audio devices.  */
    private fun updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread()
        Timber.d("updateAudioDeviceState")
        apprtcAudioManager.updateAudioDeviceState()
    }

    /** Starts timer which times out after BLUETOOTH_SCO_TIMEOUT_MS milliseconds.  */
    private fun startTimer() {
        ThreadUtils.checkIsOnMainThread()
        Timber.d("startTimer")
        handler.postDelayed(bluetoothTimeoutRunnable, BLUETOOTH_SCO_TIMEOUT_MS.toLong())
    }

    /** Cancels any outstanding timer tasks.  */
    private fun cancelTimer() {
        ThreadUtils.checkIsOnMainThread()
        Timber.d("cancelTimer")
        handler.removeCallbacks(bluetoothTimeoutRunnable)
    }

    /**
     * Called when start of the BT SCO channel takes too long time. Usually
     * happens when the BT device has been turned on during an ongoing call.
     */
    private fun bluetoothTimeout() {
        ThreadUtils.checkIsOnMainThread()
        if (bluetoothState == State.UNINITIALIZED || bluetoothHeadset == null) {
            return
        }
        Timber.d(
            "bluetoothTimeout: BT state=" + bluetoothState + ", "
                    + "attempts: " + scoConnectionAttempts + ", "
                    + "SCO is on: " + isScoOn
        )
        if (bluetoothState != State.SCO_CONNECTING) {
            return
        }
        // Bluetooth SCO should be connecting; check the latest result.
        var scoConnected = false
        val devices = bluetoothHeadset!!.connectedDevices
        if (devices.size > 0) {
            bluetoothDevice = devices[0]
            if (bluetoothHeadset!!.isAudioConnected(bluetoothDevice)) {
                Timber.d("SCO connected with " + bluetoothDevice!!.name)
                scoConnected = true
            } else {
                Timber.d("SCO is not connected with " + bluetoothDevice!!.name)
            }
        }
        if (scoConnected) {
            // We thought BT had timed out, but it's actually on; updating state.
            bluetoothState = State.SCO_CONNECTED
            scoConnectionAttempts = 0
        } else {
            // Give up and "cancel" our request by calling stopBluetoothSco().
            Timber.w("BT failed to connect after timeout")
            stopScoAudio()
        }
        updateAudioDeviceState()
        Timber.d("bluetoothTimeout done: BT state=$bluetoothState")
    }

    /** Checks whether audio uses Bluetooth SCO.  */
    private val isScoOn: Boolean
        private get() = audioManager!!.isBluetoothScoOn

    /** Converts BluetoothAdapter states into local string representations.  */
    private fun stateToString(state: Int): String {
        return when (state) {
            BluetoothAdapter.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothAdapter.STATE_CONNECTED -> "CONNECTED"
            BluetoothAdapter.STATE_CONNECTING -> "CONNECTING"
            BluetoothAdapter.STATE_DISCONNECTING -> "DISCONNECTING"
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF ->         // Indicates the local Bluetooth adapter is turning off. Local clients should immediately
                // attempt graceful disconnection of any remote links.
                "TURNING_OFF"
            BluetoothAdapter.STATE_TURNING_ON ->         // Indicates the local Bluetooth adapter is turning on. However local clients should wait
                // for STATE_ON before attempting to use the adapter.
                "TURNING_ON"
            else -> "INVALID"
        }
    }

    companion object {
        private const val TAG = "AppRTCBluetoothManager"

        // Timeout interval for starting or stopping audio to a Bluetooth SCO device.
        private const val BLUETOOTH_SCO_TIMEOUT_MS = 4000

        // Maximum number of SCO connection attempts.
        private const val MAX_SCO_CONNECTION_ATTEMPTS = 2

        /** Construction.  */
        @JvmStatic
        fun create(context: Context, audioManager: AppRTCAudioManager): AppRTCBluetoothManager {
            Timber.d("create" + AppRTCUtils.threadInfo)
            return AppRTCBluetoothManager(context, audioManager)
        }
    }

    init {
        Timber.d("ctor")
        ThreadUtils.checkIsOnMainThread()
        apprtcContext = context
        apprtcAudioManager = audioManager
        this.audioManager = getAudioManager(context)
        bluetoothState = State.UNINITIALIZED
        bluetoothServiceListener = BluetoothServiceListener()
        bluetoothHeadsetReceiver = BluetoothHeadsetBroadcastReceiver()
        handler = Handler(Looper.getMainLooper())
    }
}