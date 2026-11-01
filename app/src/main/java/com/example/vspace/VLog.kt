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

    /** 0.2.20: gabungan untuk tombol "Salin log" — vspace.log utuh (hook/boot/patch/svc) + logcat.log (crash/events). */
    fun readAll(vspaceMax: Int = 60000, logcatMax: Int = 80000): String {
        val v = try { file().readText().takeLast(vspaceMax) } catch (t: Throwable) { "(vspace.log kosong)" }
        val l = try { LogcatDump.readFile(logcatMax) } catch (t: Throwable) { "(logcat.log kosong)" }
        return "$v\n\n=== logcat.log ===\n$l"
    }

    fun clear() { try { file().delete() } catch (t: Throwable) { }; LogcatDump.clear() }
}
