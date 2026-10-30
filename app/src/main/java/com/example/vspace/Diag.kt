package com.example.vspace

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * Diagnostik 0.2.16: apa yang "terlihat" oleh kode native guest sebelum proses mati.
 * Latar belakang: libloader.so (8 Ball Pool) crash di memcpy(NULL, len-8) karena buffer path global-nya kosong.
 * Dugaan: ia mencari path APK lewat baris /proc/self/maps. Snapshot maps di bawah memverifikasi dugaan itu.
 */
object Diag {
    @Volatile private var watching = false

    /** Path unik di /proc/self/maps yang relevan (APK, /data/app, folder virtual, paket guest) beserta izinnya. */
    private fun relevantMaps(pkg: String): LinkedHashMap<String, String> {
        val res = LinkedHashMap<String, String>()
        val lines = try { File("/proc/self/maps").readLines() } catch (t: Throwable) { return res }
        for (l in lines) {
            val p = l.trim().split(Regex("\\s+"), 6)
            if (p.size < 6) continue
            val path = p[5]
            if (!path.startsWith("/")) continue
            if (path.contains(".apk") || path.contains("/data/app/") || path.contains("/virtual/") || path.contains(pkg)) {
                if (!res.containsKey(path)) res[path] = p[1]
            }
        }
        return res
    }

    /** Catat isi relevan /proc/self/maps saat ini. */
    fun snapshot(tag: String, pkg: String) {
        try {
            val m = relevantMaps(pkg)
            val sb = StringBuilder("maps[$tag]: ${m.size} path relevan")
            m.entries.take(60).forEach { sb.append("\n   ").append(it.value).append(' ').append(it.key) }
            if (m.size > 60) sb.append("\n   ... (${m.size - 60} lagi)")
            VLog.w("maps", sb.toString())
        } catch (t: Throwable) {
            VLog.e("maps", t)
        }
    }

    /** Pantau maps ~9 detik pertama: catat path BARU beserta waktunya (log ditulis sinkron, selamat dari crash native). */
    fun watch(pkg: String) {
        if (watching) return
        watching = true
        val th = Thread {
            val seen = HashSet<String>()
            val t0 = SystemClock.uptimeMillis()
            var i = 0
            while (i < 60) {
                try {
                    val fresh = relevantMaps(pkg).keys.filter { seen.add(it) }
                    if (fresh.isNotEmpty()) {
                        VLog.w("maps", "+${SystemClock.uptimeMillis() - t0}ms baru: " + fresh.joinToString(" | "))
                    }
                } catch (t: Throwable) { }
                try { Thread.sleep(150) } catch (t: InterruptedException) { break }
                i++
            }
        }
        th.isDaemon = true
        th.name = "vspace-maps-watch"
        th.start()
    }

    /** Nilai-nilai yang dilihat guest tentang dirinya + keberadaan libloader.so. */
    fun describe(app: Application, pkg: String, ai: ApplicationInfo, libDir: File) {
        try {
            val loader = File(libDir, "libloader.so")
            val loaderInfo = if (loader.exists()) "${loader.length()}B" else "tidak ada"
            VLog.w(
                "boot",
                "lingkungan guest $pkg: sourceDir=${ai.sourceDir} lib=${ai.nativeLibraryDir} data=${ai.dataDir} " +
                    "uid=${ai.uid} myUid=${Process.myUid()} 64bit=${Process.is64Bit()} " +
                    "hostApk=${app.applicationInfo.sourceDir} libloader.so=$loaderInfo"
            )
        } catch (t: Throwable) {
            VLog.e("boot", t)
        }
    }
}
