package io.livekit.android.room.participant

fun String.mimeTypeToVideoCodec(): String {
    return split("/")[1].lowercase()
}
