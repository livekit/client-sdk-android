package io.livekit.android.room.provisions

import livekit.org.webrtc.EglBase
import javax.inject.Inject
import javax.inject.Provider

/**
 * Provides access to objects used internally.
 */
// Note, to avoid accidentally instantiating an unneeded object,
// only store Providers here.
//
// Additionally, the provided objects should only be singletons.
// Otherwise the created objects may not be the one used internally.
class LKObjects
@Inject
constructor(
    private val eglBaseProvider: Provider<EglBase>,
) {
    val eglBase: EglBase
        get() = eglBaseProvider.get()
}
