package com.example.vspace

import android.os.Handler
import android.os.Looper

/**
 * Jaring pengaman main thread di proses guest: exception yang jejaknya berasal dari
 * Google Play Services (yang menolak package guest) diabaikan, bukan mematikan proses.
 * Exception lain dilempar ulang seperti biasa.
 */
object MainLoopGuard {
    fun install() {
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    return@post
                } catch (t: Throwable) {
                    if (!isGmsRelated(t)) throw t
                    VLog.w("guard", "exception Google Play Services diabaikan: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private fun isGmsRelated(t: Throwable): Boolean {
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth++ < 5) {
            val hit = c.stackTrace.take(10).any { f ->
                f.className.startsWith("com.google.android.gms") ||
                    (f.fileName?.contains("com.google.android.gms") == true)
            }
            if (hit) return true
            c = c.cause
        }
        return false
    }
}
