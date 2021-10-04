package io.livekit.android.util

import com.google.protobuf.MessageLite
import okio.ByteString
import okio.ByteString.Companion.toByteString

fun MessageLite.toOkioByteString(): ByteString {
    val byteArray = toByteArray()
    return byteArray.toByteString(0, byteArray.size)
}