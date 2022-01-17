package io.livekit.android.util

import com.google.protobuf.ByteString
import okio.ByteString.Companion.toByteString

fun com.google.protobuf.ByteString.toOkioByteString() = toByteArray().toByteString()

fun okio.ByteString.toPBByteString() = ByteString.copyFrom(toByteArray())