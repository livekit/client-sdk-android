package io.livekit.android.videoencodedecode

fun randomAlphanumericString(length: Int): String {
    val builder = StringBuilder(length)
    for (i in 0 until length) {
        val value = (0..61).random()
        builder.append(value.toAlphaNumeric())
    }
    return builder.toString()
}

fun Int.toAlphaNumeric(): Char {
    if (this < 0 || this > 62) {
        throw IllegalArgumentException()
    }

    var offset = this
    if (offset < 10) {
        return '0' + offset
    }

    offset -= 10
    if (offset < 26) {
        return 'a' + offset
    }
    offset -= 26
    if (offset < 26) {
        return 'A' + offset
    }
    throw IllegalArgumentException()
}
