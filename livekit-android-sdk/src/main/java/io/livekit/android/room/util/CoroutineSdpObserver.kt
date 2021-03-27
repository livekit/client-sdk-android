package io.livekit.android.room.util

import io.livekit.android.util.Either
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

open class CoroutineSdpObserver : SdpObserver {
    private var createOutcome: Either<SessionDescription, String?>? = null
        set(value) {
            field = value
            if (value != null) {
                val conts = pendingCreate.toList()
                pendingCreate.clear()
                conts.forEach {
                    it.resume(value)
                }
            }
        }
    private var pendingCreate = mutableListOf<Continuation<Either<SessionDescription, String?>>>()

    private var setOutcome: Either<Unit, String?>? = null
        set(value) {
            field = value
            if (value != null) {
                val conts = pendingSets.toList()
                pendingSets.clear()
                conts.forEach {
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

    suspend fun awaitCreate() = suspendCoroutine<Either<SessionDescription, String?>> { cont ->
        val curOutcome = createOutcome
        if (curOutcome != null) {
            cont.resume(curOutcome)
        } else {
            pendingCreate.add(cont)
        }
    }

    suspend fun awaitSet() = suspendCoroutine<Either<Unit, String?>> { cont ->
        val curOutcome = setOutcome
        if (curOutcome != null) {
            cont.resume(curOutcome)
        } else {
            pendingSets.add(cont)
        }
    }
}

suspend fun PeerConnection.createOffer(constraints: MediaConstraints): Either<SessionDescription, String?> {
    val observer = CoroutineSdpObserver()
    this.createOffer(observer, constraints)
    return observer.awaitCreate()
}

suspend fun PeerConnection.createAnswer(constraints: MediaConstraints): Either<SessionDescription, String?> {
    val observer = CoroutineSdpObserver()
    this.createAnswer(observer, constraints)
    return observer.awaitCreate()
}

suspend fun PeerConnection.setRemoteDescription(description: SessionDescription): Either<Unit, String?> {
    val observer = CoroutineSdpObserver()
    this.setRemoteDescription(observer, description)
    return observer.awaitSet()
}

suspend fun PeerConnection.setLocalDescription(description: SessionDescription): Either<Unit, String?> {
    val observer = CoroutineSdpObserver()
    this.setLocalDescription(observer, description)
    return observer.awaitSet()
}