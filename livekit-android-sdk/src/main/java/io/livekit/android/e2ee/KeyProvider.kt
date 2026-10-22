/*
 * Copyright 2023-2026 LiveKit, Inc.
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

package io.livekit.android.e2ee

import io.livekit.android.util.LKLog
import livekit.org.webrtc.FrameCryptorFactory
import livekit.org.webrtc.FrameCryptorKeyDerivationAlgorithm
import livekit.org.webrtc.FrameCryptorKeyProvider

internal class KeyInfo(var participantId: String, var keyIndex: Int, var key: String) {
    override fun toString(): String {
        return "KeyInfo(participantId='$participantId', keyIndex=$keyIndex)"
    }
}

interface KeyProvider {
    fun setSharedKey(key: String, keyIndex: Int? = 0): Boolean
    fun ratchetSharedKey(keyIndex: Int? = 0): ByteArray
    fun exportSharedKey(keyIndex: Int? = 0): ByteArray
    fun setKey(key: String, participantId: String?, keyIndex: Int? = 0)
    fun ratchetKey(participantId: String, keyIndex: Int? = 0): ByteArray
    fun exportKey(participantId: String, keyIndex: Int? = 0): ByteArray
    fun setSifTrailer(trailer: ByteArray)
    fun getLatestKeyIndex(participantId: String): Int

    val rtcKeyProvider: FrameCryptorKeyProvider

    var enableSharedKey: Boolean
}

/**
 * Implemented by [KeyProvider]s that track the key index the local
 * participant encrypts with and can push changes to observers.
 *
 * [E2EEManager] subscribes so the frame cryptors of media tracks that are
 * already published get re-stamped whenever the index changes. Receivers
 * are unaffected: they read the index from each incoming frame.
 */
interface KeyIndexObservable {

    /**
     * The key index the local participant encrypts with.
     */
    val currentKeyIndex: Int

    /**
     * Registers [observer] under [owner]; [removeKeyIndexObserver] with the
     * same owner detaches it. The observer is invoked with the latest index
     * on every change and may be called from any thread.
     */
    fun addKeyIndexObserver(owner: Any, observer: (keyIndex: Int) -> Unit)

    /**
     * Detaches the observer previously registered under [owner].
     */
    fun removeKeyIndexObserver(owner: Any)
}

class BaseKeyProvider(
    ratchetSalt: String = defaultRatchetSalt,
    uncryptedMagicBytes: String = defaultMagicBytes,
    ratchetWindowSize: Int = defaultRatchetWindowSize,
    override var enableSharedKey: Boolean = true,
    failureTolerance: Int = defaultFailureTolerance,
    keyRingSize: Int = defaultKeyRingSize,
    discardFrameWhenCryptorNotReady: Boolean = defaultDiscardFrameWhenCryptorNotReady,
    keyDerivationAlgorithm: FrameCryptorKeyDerivationAlgorithm = defaultKeyDerivationAlgorithm,
) : KeyProvider, KeyIndexObservable {

    private val keyRingSize: Int = keyRingSize
    private val keyIndexStateLock = Any()
    private val keyIndexDispatchLock = Any()
    private val keyIndexObservers = mutableMapOf<Any, (Int) -> Unit>()

    @Volatile
    private var _currentKeyIndex: Int = 0

    override val currentKeyIndex: Int
        get() = _currentKeyIndex

    override val rtcKeyProvider: FrameCryptorKeyProvider = FrameCryptorFactory.createFrameCryptorKeyProvider(
        enableSharedKey,
        ratchetSalt.toByteArray(),
        ratchetWindowSize,
        uncryptedMagicBytes.toByteArray(),
        failureTolerance,
        keyRingSize,
        discardFrameWhenCryptorNotReady,
        keyDerivationAlgorithm,
    )

    override fun setSharedKey(key: String, keyIndex: Int?): Boolean {
        return rtcKeyProvider.setSharedKey(keyIndex ?: 0, key.toByteArray()).also { succeeded ->
            if (succeeded) {
                setCurrentKeyIndex(keyIndex ?: 0)
            }
        }
    }

    override fun ratchetSharedKey(keyIndex: Int?): ByteArray {
        return rtcKeyProvider.ratchetSharedKey(keyIndex ?: 0)
    }

    override fun exportSharedKey(keyIndex: Int?): ByteArray {
        return rtcKeyProvider.exportSharedKey(keyIndex ?: 0)
    }

    /**
     * Set a key for a participant
     * @param key
     * @param participantId
     * @param keyIndex
     */
    override fun setKey(key: String, participantId: String?, keyIndex: Int?) {
        if (enableSharedKey) {
            return
        }

        if (participantId == null) {
            LKLog.d { "Please provide valid participantId for non-SharedKey mode." }
            return
        }

        rtcKeyProvider.setKey(participantId, keyIndex ?: 0, key.toByteArray())
    }

    override fun ratchetKey(participantId: String, keyIndex: Int?): ByteArray {
        return rtcKeyProvider.ratchetKey(participantId, keyIndex ?: 0)
    }

    override fun exportKey(participantId: String, keyIndex: Int?): ByteArray {
        return rtcKeyProvider.exportKey(participantId, keyIndex ?: 0)
    }

    override fun setSifTrailer(trailer: ByteArray) {
        rtcKeyProvider.setSifTrailer(trailer)
    }

    /**
     * Switches the local participant's encryption to the key at [keyIndex]
     * (modulo the key ring size).
     *
     * Takes effect immediately for the media tracks already published and
     * for every data packet sent afterwards. Receivers are unaffected: they
     * read the index from each frame.
     */
    fun setCurrentKeyIndex(keyIndex: Int) {
        val observers = synchronized(keyIndexStateLock) {
            _currentKeyIndex = Math.floorMod(keyIndex, keyRingSize)
            keyIndexObservers.values.toList()
        }
        // Dispatch is serialized and each pass re-reads the newest index,
        // so overlapping rotations can't leave senders on a stale index.
        synchronized(keyIndexDispatchLock) {
            val latest = _currentKeyIndex
            observers.forEach { it(latest) }
        }
    }

    /**
     * The key index outgoing media frames and data packets are stamped with.
     * In non-shared key mode [setKey] does not move it — the key may belong
     * to a remote participant — so switch the local one with
     * [setCurrentKeyIndex].
     */
    override fun getLatestKeyIndex(participantId: String): Int {
        return currentKeyIndex
    }

    override fun addKeyIndexObserver(owner: Any, observer: (keyIndex: Int) -> Unit) {
        synchronized(keyIndexStateLock) { keyIndexObservers[owner] = observer }
    }

    override fun removeKeyIndexObserver(owner: Any) {
        synchronized(keyIndexStateLock) { keyIndexObservers.remove(owner) }
    }
}
