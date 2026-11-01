package com.example.vspace

import java.io.File

/**
 * Ambil logcat milik UID sendiri (guest berjalan dengan UID host, jadi crash/kill terlihat).
 *
 * 0.2.20:
 *  - Hasil disimpan ke file TERPISAH `filesDir/logcat.log` (tidak lagi dicampur ke vspace.log). Tombol "Salin log"
 *    menggabungkan vspace.log (utuh, dari paling awal) + logcat.log (utuh), jadi hook/boot/patch tidak terpotong.
 *  - Tambah buffer `events` (am_proc_died / am_kill / am_proc_start): melihat SIAPA yang membunuh proses guest
 *    ketika mati diam-diam tanpa Fatal signal (mis. CODM: aplikasi keluar sendiri ~3 detik setelah start).
 *  - Error main dikelompokkan per-pid dan di-dedupe (baris Firebase/GMS dari SDK guest tidak membanjiri).
 *  - Bagian crash tetap di PALING BAWAH supaya tidak terpotong saat disalin.
 */
object LogcatDump {
    private fun read(cmd: List<String>): List<String> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        return lines
    }

    fun file() = File(VApp.instance.filesDir, "logcat.log")

    fun readFile(max: Int = 120000): String = try {
        file().readText().takeLast(max).ifEmpty { "(logcat kosong, tekan Ambil logcat dulu)" }
    } catch (t: Throwable) { "(logcat kosong)" }

    fun clear() { try { file().delete() } catch (t: Throwable) { } }

    fun capture(): String {
        val out = StringBuilder()
        val ver = try { VApp.instance.packageManager.getPackageInfo(VApp.instance.packageName, 0).versionName } catch (t: Throwable) { "?" }
        val t0 = System.currentTimeMillis()
        val hhmmss = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(t0))
        out.append("== VSpace v$ver, capture at $hhmmss\n")

        // events: proses start/died/kill (maks 100 baris, hanya yang relevan)
        try {
            val ev = read(listOf("logcat", "-b", "events", "-d", "-v", "time", "-t", "600"))
            val keep = ev.filter {
                it.contains("am_proc_died") || it.contains("am_kill") || it.contains("am_proc_start") ||
                    it.contains("am_crash") || it.contains("am_anr")
            }
            out.append("== events (proc lifecycle: ${keep.size}/${ev.size} baris)\n")
            keep.takeLast(80).forEach { out.append(it.take(240)).append('\n') }
        } catch (t: Throwable) {
            out.append("events gagal: ${t.message}\n")
        }

        // main: hanya error/fatal, dedupe per pid
        try {
            val main = read(listOf("logcat", "-b", "main", "-d", "-v", "time", "-t", "8000"))
            val rePid = Regex("\\(\\s*(\\d+)\\):")
            val perPid = LinkedHashMap<String, MutableList<String>>()
            for (l in main) {
                val hasErr = l.contains(" E/") || l.contains(" F/") || l.contains("Fatal signal") ||
                    l.contains("SIGSEGV") || l.contains("SIGABRT")
                if (!hasErr) continue
                val body = l.substringAfter("): ", "")
                if (body.trimStart().startsWith("at ")) continue
                val pid = rePid.find(l)?.groupValues?.get(1) ?: "?"
                perPid.getOrPut(pid) { ArrayList() }.add(l)
            }
            out.append("== main errors (${perPid.size} pid)\n")
            for ((pid, list) in perPid) {
                // dedupe dengan kunci tag+potongan pesan
                val seen = HashSet<String>()
                val uniq = list.filter { l ->
                    val tag = l.substringAfter("): ", "").trimStart().takeWhile { it != ':' }
                    val body = l.substringAfter("): ", "").take(60)
                    seen.add("$tag|$body")
                }
                out.append("-- pid $pid (${list.size} baris, ${uniq.size} unik)\n")
                uniq.takeLast(30).forEach { out.append(it.take(220)).append('\n') }
            }
        } catch (t: Throwable) {
            out.append("main gagal: ${t.message}\n")
        }

        // crash: ambil ekor, taruh PALING BAWAH (0.2.18 rule)
        var crash: List<String> = emptyList()
        try { crash = read(listOf("logcat", "-b", "crash", "-d", "-v", "time", "-t", "300")) } catch (t: Throwable) {}
        out.append("== crash buffer (${crash.size} baris)\n")
        crash.takeLast(80).forEach { out.append(it.take(220)).append('\n') }
        val fi = crash.indexOfLast { it.contains("Fatal signal") }
        if (fi >= 0) {
            out.append("== RINGKAS CRASH NATIVE\n")
            out.append(crash[fi].take(240)).append('\n')
            val frames = Regex("#\\d+ pc ")
            crash.drop(fi).filter { frames.containsMatchIn(it) }.take(10).forEach {
                out.append(it.substringAfter("): ", it).trim().take(240)).append('\n')
            }
        }
        val txt = out.toString()
        try { file().writeText(txt) } catch (t: Throwable) { }
        return txt
    }
}