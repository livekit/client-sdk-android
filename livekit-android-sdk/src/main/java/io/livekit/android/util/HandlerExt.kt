package io.livekit.android.util

import android.os.Handler
import android.os.Looper

fun Handler.runOrPost(r: Runnable) {
    if (Looper.myLooper() == this.looper) {
        r.run()
    } else {
        post(r)
    }
}
