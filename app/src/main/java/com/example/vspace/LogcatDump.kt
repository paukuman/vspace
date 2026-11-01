package com.example.vspace

import java.io.File

/**
 * Ambil logcat milik UID sendiri (guest = UID host, jadi crash/kill terlihat).
 *
 * 0.2.21: buffer `events`+`system` (MIUI A10 taruh am_kill/lowmemorykiller di system, bukan events —
 * terbukti log 0.2.20 events 0/125 padahal CODM pid 13819 & 8BP pid 14290 mati). Filter LIFE diperluas.
 * Kalau tidak cocok, tampilkan 20 baris terakhir sebagai sampel. Spam FA/Firebase/AppLovin dibuang.
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

    private val LIFE = Regex(
        "am_proc_start|am_proc_died|am_kill|am_crash|am_anr|am_low_memory|am_restart|" +
        "lowmemorykiller|lmkd|SIGKILL|SIGSEGV|SIGABRT| died| killed"
    )
    private val SPAM_TAG = setOf(
        "FA", "FirebaseMessaging", "FirebaseInitProvider", "AppLovinSdk", "AppLovin",
        "FirebaseAnalytics", "FirebaseSessions"
    )

    private fun dumpBuffer(out: StringBuilder, name: String, max: Int, fallback: Int) {
        try {
            val raw = read(listOf("logcat", "-b", name, "-d", "-v", "time", "-t", max.toString()))
            val keep = raw.filter { LIFE.containsMatchIn(it) }
            out.append("== buffer $name (${keep.size}/${raw.size} cocok)\n")
            if (keep.isEmpty() && raw.isNotEmpty()) {
                out.append("-- (tidak ada cocok, $fallback baris terakhir sampel)\n")
                raw.takeLast(fallback).forEach { out.append(it.take(240)).append('\n') }
            } else {
                keep.takeLast(120).forEach { out.append(it.take(240)).append('\n') }
            }
        } catch (t: Throwable) {
            out.append("buffer $name gagal: ${t.message}\n")
        }
    }

    fun capture(): String {
        val out = StringBuilder()
        val ver = try { VApp.instance.packageManager.getPackageInfo(VApp.instance.packageName, 0).versionName } catch (t: Throwable) { "?" }
        val hhmmss = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        out.append("== VSpace v$ver, capture at $hhmmss\n")

        dumpBuffer(out, "events", 3000, 20)
        dumpBuffer(out, "system", 3000, 20)

        try {
            val main = read(listOf("logcat", "-b", "main", "-d", "-v", "time", "-t", "8000"))
            val perPid = LinkedHashMap<String, MutableList<String>>()
            val rePid = Regex("\\(\\s*(\\d+)\\):")
            val reTag = Regex(" [A-Z]/(\\S+)")
            for (l in main) {
                val isErr = l.contains(" E/") || l.contains(" F/") || l.contains("Fatal signal") ||
                    l.contains("SIGSEGV") || l.contains("SIGABRT") || l.contains("SIGKILL")
                if (!isErr) continue
                val tag = reTag.find(l)?.groupValues?.get(1) ?: ""
                if (tag in SPAM_TAG) continue
                val pid = rePid.find(l)?.groupValues?.get(1) ?: "?"
                perPid.getOrPut(pid) { ArrayList() }.add(l)
            }
            out.append("== main errors (${perPid.size} pid, spam FA/Firebase/AppLovin dibuang)\n")
            for ((pid, list) in perPid) {
                val seen = HashSet<String>()
                val uniq = list.filter { l ->
                    val tag = reTag.find(l)?.groupValues?.get(1) ?: ""
                    val body = l.substringAfter("): ", "").take(80)
                    seen.add("$tag|$body")
                }
                out.append("-- pid $pid (${list.size} baris, ${uniq.size} unik)\n")
                uniq.takeLast(30).forEach { out.append(it.take(220)).append('\n') }
            }
        } catch (t: Throwable) {
            out.append("main gagal: ${t.message}\n")
        }

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