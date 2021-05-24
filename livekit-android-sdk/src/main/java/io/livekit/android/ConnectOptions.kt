package io.livekit.android


class ConnectOptions(
    var autoSubscribe: Boolean = true
) {
    internal var reconnect: Boolean = false
}
