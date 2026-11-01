package com.example.vspace

import android.app.Application
import android.util.Log
import java.io.File

/** Log ke file (dipakai bersama oleh semua proses) supaya bisa dibaca dari UI tanpa adb. */
object VLog {
    private fun file() = File(VApp.instance.filesDir, "vspace.log")
    private fun bootFile() = File(VApp.instance.filesDir, "boot.log")

    @Synchronized
    fun w(tag: String, msg: String) {
        Log.d("VSpace", "$tag: $msg")
        try {
            val proc = try { Application.getProcessName() } catch (t: Throwable) { "?" }
            file().appendText("[${proc.substringAfter(':', "main")}] $tag: $msg\n")
        } catch (t: Throwable) { /* abaikan */ }
    }

    /** 0.2.23: log khusus boot/diag/ext agar tidak terpotong takeLast(60000) karena spam hook. */
    @Synchronized
    fun bootOnly(tag: String, msg: String) {
        Log.d("VSpace", "boot/$tag: $msg")
        try {
            val proc = try { Application.getProcessName() } catch (t: Throwable) { "?" }
            val hhmmss = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            bootFile().appendText("$hhmmss [${proc.substringAfter(':', "main")}] $tag: $msg\n")
        } catch (t: Throwable) { /* abaikan */ }
    }

    fun e(tag: String, t: Throwable) = w(tag, Log.getStackTraceString(t))
    fun bootE(tag: String, t: Throwable) = bootOnly(tag, Log.getStackTraceString(t))

    fun read(max: Int = 8000): String = try {
        file().readText().takeLast(max).ifEmpty { "(log kosong)" }
    } catch (t: Throwable) { "(log kosong)" }

    fun bootRead(max: Int = 120000): String = try {
        bootFile().readText().takeLast(max).ifEmpty { "(boot.log kosong — belum ada boot guest sejak versi ini)" }
    } catch (t: Throwable) { "(boot.log kosong)" }

    fun readAll(vspaceMax: Int = 60000, logcatMax: Int = 80000): String {
        val v = try { file().readText().takeLast(vspaceMax) } catch (t: Throwable) { "(vspace.log kosong)" }
        val b = try { bootFile().readText().takeLast(120000) } catch (t: Throwable) { "" }
        val l = try { LogcatDump.readFile(logcatMax) } catch (t: Throwable) { "(logcat.log kosong)" }
        return "$v\n\n=== boot.log ===\n$b\n\n=== logcat.log ===\n$l"
    }

    fun clear() {
        try { file().delete() } catch (t: Throwable) { }
        try { bootFile().delete() } catch (t: Throwable) { }
        LogcatDump.clear()
    }
}