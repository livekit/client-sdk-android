package io.livekit.android.mock

import org.webrtc.DataChannel

class MockDataChannel(private val label: String?) : DataChannel(1L) {

    var observer: DataChannel.Observer? = null
    override fun registerObserver(observer: Observer?) {
        this.observer = observer
    }

    override fun unregisterObserver() {
        observer = null
    }

    override fun label(): String? {
        return label
    }

    override fun id(): Int {
        return 0
    }

    override fun state(): State {
        return State.OPEN
    }

    override fun bufferedAmount(): Long {
        return 0
    }

    override fun send(buffer: Buffer?): Boolean {
        return true
    }

    override fun close() {
    }

    override fun dispose() {
    }
}