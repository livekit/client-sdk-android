/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android.room.util

import io.livekit.android.util.Either
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal open class CoroutineSdpObserver : SdpObserver {

    private val stateLock = Mutex()
    private var createOutcome: Either<SessionDescription, String?>? = null
        set(value) {
            val conts = runBlocking {
                stateLock.withLock {
                    field = value
                    if (value != null) {
                        val conts = pendingCreate.toList()
                        pendingCreate.clear()
                        conts
                    } else {
                        null
                    }
                }
            }
            if (value != null) {
                conts?.forEach {
                    it.resume(value)
                }
            }
        }

    private var pendingCreate = mutableListOf<Continuation<Either<SessionDescription, String?>>>()

    private var setOutcome: Either<Unit, String?>? = null
        set(value) {
            val conts = runBlocking {
                stateLock.withLock {
                    field = value
                    if (value != null) {
                        val conts = pendingSets.toList()
                        pendingSets.clear()
                        conts
                    } else {
                        null
                    }
                }
            }
            if (value != null) {
                conts?.forEach {
                    it.resume(value)
                }
            }
        }
    private var pendingSets = mutableListOf<Continuation<Either<Unit, String?>>>()

    override fun onCreateSuccess(sdp: SessionDescription?) {
        createOutcome = if (sdp == null) {
            Either.Right("empty sdp")
        } else {
            Either.Left(sdp)
        }
    }

    override fun onSetSuccess() {
        setOutcome = Either.Left(Unit)
    }

    override fun onCreateFailure(message: String?) {
        createOutcome = Either.Right(message)
    }

    override fun onSetFailure(message: String?) {
        setOutcome = Either.Right(message)
    }

    suspend fun awaitCreate() = suspendCancellableCoroutine { cont ->
        val unlockedOutcome = createOutcome
        if (unlockedOutcome != null) {
            cont.resume(unlockedOutcome)
        } else {
            runBlocking {
                stateLock.lock()
                val lockedOutcome = createOutcome
                if (lockedOutcome != null) {
                    stateLock.unlock()
                    cont.resume(lockedOutcome)
                } else {
                    pendingCreate.add(cont)
                    stateLock.unlock()
                }
            }
        }
    }

    suspend fun awaitSet() = suspendCoroutine { cont ->
        val unlockedOutcome = setOutcome
        if (unlockedOutcome != null) {
            cont.resume(unlockedOutcome)
        } else {
            runBlocking {
                stateLock.lock()
                val lockedOutcome = setOutcome
                if (lockedOutcome != null) {
                    stateLock.unlock()
                    cont.resume(lockedOutcome)
                } else {
                    pendingSets.add(cont)
                    stateLock.unlock()
                }
            }
        }
    }
}

internal suspend fun PeerConnection.createOffer(constraints: MediaConstraints): Either<SessionDescription, String?> {
    val observer = CoroutineSdpObserver()
    this.createOffer(observer, constraints)
    return observer.awaitCreate()
}

internal suspend fun PeerConnection.createAnswer(constraints: MediaConstraints): Either<SessionDescription, String?> {
    val observer = CoroutineSdpObserver()
    this.createAnswer(observer, constraints)
    return observer.awaitCreate()
}

internal suspend fun PeerConnection.setRemoteDescription(description: SessionDescription): Either<Unit, String?> {
    val observer = CoroutineSdpObserver()
    this.setRemoteDescription(observer, description)
    return observer.awaitSet()
}

internal suspend fun PeerConnection.setLocalDescription(description: SessionDescription): Either<Unit, String?> {
    val observer = CoroutineSdpObserver()
    this.setLocalDescription(observer, description)
    return observer.awaitSet()
}
