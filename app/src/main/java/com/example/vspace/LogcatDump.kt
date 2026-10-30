package com.example.vspace

/**
 * Ambil logcat milik UID sendiri (guest berjalan dengan UID host, jadi crash native/kill terlihat).
 * Disaring ke baris error/fatal supaya muat di log.
 */
object LogcatDump {
    fun capture(): String {
        val out = StringBuilder()
        val sets = listOf(
            "crash" to listOf("logcat", "-b", "crash", "-d", "-v", "time", "-t", "150"),
            "main" to listOf("logcat", "-b", "main", "-d", "-v", "time", "-t", "2500")
        )
        for ((name, cmd) in sets) {
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val lines = p.inputStream.bufferedReader().readLines()
                p.waitFor()
                val keep = if (name == "crash") lines else lines.filter { l ->
                    l.contains(" F/") || l.contains(" E/") || l.contains("Fatal signal") ||
                        l.contains("SIGSEGV") || l.contains("abort", true) ||
                        l.contains("killed", true) || l.contains("signal ", true)
                }
                out.append("== logcat $name (${keep.size}/${lines.size} baris)\n")
                keep.takeLast(70).forEach { out.append(it.take(300)).append('\n') }
            } catch (t: Throwable) {
                out.append("logcat $name gagal: ${t.message}\n")
            }
        }
        return out.toString()
    }
}
