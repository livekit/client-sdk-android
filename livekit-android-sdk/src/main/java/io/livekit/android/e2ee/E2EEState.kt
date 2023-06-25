package io.livekit.android.e2ee

enum class E2EEState {
    NEW,
    OK,
    KEY_RATCHETED,
    MISSING_KEY,
    ENCRYPTION_FAILED,
    DECRYPTION_FAILED,
    INTERNAL_ERROR;
}