package io.livekit.android.e2ee

enum class E2EEState {
    NEW, // initial state
    OK,  // encryption or decryption succeeded
    KEY_RATCHETED, // key ratcheted
    MISSING_KEY, // missing key
    ENCRYPTION_FAILED, // encryption failed
    DECRYPTION_FAILED, // decryption failed
    INTERNAL_ERROR // internal error
}