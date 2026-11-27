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
import java.util.concurrent.ConcurrentHashMap

internal class KeyInfo(var participantId: String, var keyIndex: Int, var key: String) {
    override fun toString(): String {
        return "KeyInfo(participantId='$participantId', keyIndex=$keyIndex)"
    }
}

/**
 * The length of the AES-GCM key derived from the shared or per-participant key material.
 *
 * Every participant in a room must use the same size: a 256-bit client and a 128-bit client
 * cannot decrypt each other's media or data. The default is [BITS_128], which matches the
 * historical behavior of all SDKs.
 */
enum class KeyProviderKeySize(val bits: Int) {
    BITS_128(128),
    BITS_256(256),
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
 * Implemented by [KeyProvider]s that report when the index returned by
 * [KeyProvider.getLatestKeyIndex] changes.
 *
 * [E2EEManager] subscribes so the frame cryptors of tracks the local
 * participant has already published are re-stamped with the new index;
 * without it, only senders created after the change pick it up. Receivers
 * are unaffected: they read the index from each incoming frame.
 */
interface KeyIndexObservable {

    /**
     * Registers [observer] under [owner]; [removeKeyIndexObserver] with the
     * same owner detaches it. [observer] receives the participant whose latest
     * key index was written, or `null` when the shared key index was, plus the
     * index written. It may be called from any thread.
     */
    fun addKeyIndexObserver(owner: Any, observer: (participantId: String?, keyIndex: Int) -> Unit)

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
    keySize: KeyProviderKeySize = defaultKeySize,
) : KeyProvider, KeyIndexObservable {

    // The native key ring is clamped this way (<= 0 -> default, > 256 -> 256) and wraps an
    // installed index by its size. Recorded indexes must be the slot actually written: senders
    // are stamped with them, and native looks the stamp up without a bounds check.
    private val effectiveKeyRingSize: Int =
        if (keyRingSize <= 0) defaultKeyRingSize else minOf(keyRingSize, maxKeyRingSize)

    // Written from the app's thread, read on the RTC thread by E2EEManager.
    private val latestSetIndex = ConcurrentHashMap<String, Int>()

    @Volatile
    private var sharedKeyIndex: Int = 0

    private val keyIndexObservers = ConcurrentHashMap<Any, (String?, Int) -> Unit>()

    override val rtcKeyProvider: FrameCryptorKeyProvider = FrameCryptorFactory.createFrameCryptorKeyProvider(
        enableSharedKey,
        ratchetSalt.toByteArray(),
        ratchetWindowSize,
        uncryptedMagicBytes.toByteArray(),
        failureTolerance,
        keyRingSize,
        discardFrameWhenCryptorNotReady,
        keyDerivationAlgorithm,
        keySize.bits,
    )

    /**
     * Installs the shared key at [keyIndex] and, on success, makes it the index
     * the local participant encrypts with; already-published tracks follow.
     *
     * Like the native key provider, an index past the key ring wraps to
     * `keyIndex % keyRingSize`. A negative [keyIndex] replaces the key in the
     * current shared slot without moving it.
     */
    override fun setSharedKey(key: String, keyIndex: Int?): Boolean {
        val index = keyIndex ?: 0
        val installed = rtcKeyProvider.setSharedKey(sharedSlot(index), key.toByteArray())
        if (installed && index >= 0) {
            val slot = index % effectiveKeyRingSize
            sharedKeyIndex = slot
            notifyKeyIndexObservers(null, slot)
        }
        return installed
    }

    /**
     * Ratchets the shared key at [keyIndex], or at the current shared slot when
     * [keyIndex] is `null` or negative.
     */
    override fun ratchetSharedKey(keyIndex: Int?): ByteArray {
        return rtcKeyProvider.ratchetSharedKey(sharedSlot(keyIndex ?: -1))
    }

    // Native fans shared installs and ratchets out to every participant's key handler with
    // the index it was given, and each handler resolves -1 to its own current slot, which
    // differs per participant once a receiver has auto-ratcheted. So shared operations
    // never pass a negative index to native: it resolves to the recorded shared slot.
    private fun sharedSlot(index: Int): Int = if (index >= 0) index else sharedKeyIndex

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

        val index = keyIndex ?: 0
        rtcKeyProvider.setKey(participantId, index, key.toByteArray())

        // Record only what senders are stamped with: a non-negative index wraps by the
        // ring size like native. A negative one installs into native's current slot
        // and doesn't move the stamped index, so there is nothing to record or re-stamp.
        if (index >= 0) {
            val slot = index % effectiveKeyRingSize
            latestSetIndex[participantId] = slot
            notifyKeyIndexObservers(participantId, slot)
        }
    }

    /**
     * Ratchets [participantId]'s key at [keyIndex], or at native's current slot
     * for that participant when [keyIndex] is `null`. Unlike the shared key, this
     * reaches only that participant's handler, whose current slot follows the
     * slot their sender encrypts with.
     */
    override fun ratchetKey(participantId: String, keyIndex: Int?): ByteArray {
        return rtcKeyProvider.ratchetKey(participantId, keyIndex ?: NATIVE_CURRENT_SLOT)
    }

    override fun exportKey(participantId: String, keyIndex: Int?): ByteArray {
        return rtcKeyProvider.exportKey(participantId, keyIndex ?: 0)
    }

    override fun setSifTrailer(trailer: ByteArray) {
        rtcKeyProvider.setSifTrailer(trailer)
    }

    /**
     * The key index [participantId] last installed with [setKey], or in shared
     * key mode the index last installed with [setSharedKey].
     */
    override fun getLatestKeyIndex(participantId: String): Int {
        return if (enableSharedKey) sharedKeyIndex else latestSetIndex[participantId] ?: 0
    }

    override fun addKeyIndexObserver(owner: Any, observer: (participantId: String?, keyIndex: Int) -> Unit) {
        keyIndexObservers[owner] = observer
    }

    override fun removeKeyIndexObserver(owner: Any) {
        keyIndexObservers.remove(owner)
    }

    private fun notifyKeyIndexObservers(participantId: String?, keyIndex: Int) {
        keyIndexObservers.values.forEach { it(participantId, keyIndex) }
    }

    private companion object {
        // Native ratchets a participant handler's own current slot for -1. Per-participant
        // implicit ratchets defer to that pointer: it follows the slot a remote sender
        // encrypts with, which this provider doesn't track.
        const val NATIVE_CURRENT_SLOT = -1
    }
}
