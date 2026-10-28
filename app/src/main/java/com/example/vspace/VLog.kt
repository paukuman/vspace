package com.example.vspace

import android.app.Application
import android.util.Log
import java.io.File

/** Log ke file (dipakai bersama oleh semua proses) supaya bisa dibaca dari UI tanpa adb. */
object VLog {
    private fun file() = File(VApp.instance.filesDir, "vspace.log")

    @Synchronized
    fun w(tag: String, msg: String) {
        Log.d("VSpace", "$tag: $msg")
        try {
            val proc = try { Application.getProcessName() } catch (t: Throwable) { "?" }
            file().appendText("[${proc.substringAfter(':', "main")}] $tag: $msg\n")
        } catch (t: Throwable) { /* abaikan */ }
    }

    fun e(tag: String, t: Throwable) = w(tag, Log.getStackTraceString(t))

    fun read(max: Int = 8000): String = try {
        file().readText().takeLast(max).ifEmpty { "(log kosong)" }
    } catch (t: Throwable) { "(log kosong)" }

    fun clear() { try { file().delete() } catch (t: Throwable) { } }
}
