package com.example.vspace

/**
 * Ambil logcat milik UID sendiri (guest berjalan dengan UID host, jadi crash native/kill terlihat).
 * 0.2.16: selain ringkasan error, tampilkan SEMUA baris (semua level) milik pid yang crash.
 */
object LogcatDump {
    private fun read(cmd: List<String>): List<String> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        return lines
    }

    fun capture(): String {
        val out = StringBuilder()
        var crash: List<String> = emptyList()
        try {
            crash = read(listOf("logcat", "-b", "crash", "-d", "-v", "time", "-t", "150"))
        } catch (t: Throwable) {
            out.append("logcat crash gagal: ${t.message}\n")
        }

        try {
            val main = read(listOf("logcat", "-b", "main", "-d", "-v", "time", "-t", "6000"))
            val fatalIdx = crash.indexOfLast { it.contains("Fatal signal") }
            val pid = crash.getOrNull(fatalIdx)
                ?.let { Regex("pid (\\d+)").find(it)?.groupValues?.get(1) }
            val err = main.filter { l ->
                !l.substringAfter("): ", "").trimStart().startsWith("at ") &&
                (l.contains(" F/") || l.contains(" E/") || l.contains("Fatal signal") ||
                    l.contains("SIGSEGV") || l.contains("abort", true) ||
                    l.contains("killed", true) || l.contains("signal ", true))
            }
            out.append("== logcat main, error umum (${err.size}/${main.size} baris)\n")
            err.takeLast(40).forEach { out.append(it.take(200)).append('\n') }
            if (pid != null) {
                val re = Regex("\\(\\s*" + pid + "\\):")
                val mine = main.filter { re.containsMatchIn(it) }
                out.append("== logcat main, pid $pid, semua level (${mine.size}/${main.size} baris)\n")
                mine.takeLast(80).forEach { out.append(it.take(180)).append('\n') }
            }
        } catch (t: Throwable) {
            out.append("logcat main gagal: ${t.message}\n")
        }

        // 0.2.18: bagian crash ditaruh PALING BAWAH supaya tidak terpotong saat log disalin.
        out.append("== logcat crash (${crash.size} baris)\n")
        crash.takeLast(40).forEach { out.append(it.take(200)).append('\n') }
        val fatalIdx = crash.indexOfLast { it.contains("Fatal signal") }
        if (fatalIdx >= 0) {
            out.append("== RINGKAS CRASH NATIVE\n")
            out.append(crash[fatalIdx].take(220)).append('\n')
            val frames = Regex("#\\d+ pc ")
            crash.drop(fatalIdx).filter { frames.containsMatchIn(it) }.take(10).forEach {
                out.append(it.substringAfter("): ", it).trim().take(220)).append('\n')
            }
        }
        return out.toString()
    }
}
