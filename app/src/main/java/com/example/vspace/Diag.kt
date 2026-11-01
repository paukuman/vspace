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
            // 0.2.28: folder guest kini "files/p/" (sebelumnya "files/virtual/").
            // Keduanya tetap dikenali supaya log lama tidak berubah arti.
            if (path.contains(".apk") || path.contains("/data/app/") ||
                path.contains("/p/") || path.contains("/virtual/") || path.contains(pkg)) {
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
            VLog.bootOnly(
                "boot",
                "lingkungan guest $pkg: sourceDir=${ai.sourceDir} lib=${ai.nativeLibraryDir} data=${ai.dataDir} " +
                    "uid=${ai.uid} myUid=${Process.myUid()} 64bit=${Process.is64Bit()} " +
                    "hostApk=${app.applicationInfo.sourceDir} libloader.so=$loaderInfo"
            )
        } catch (t: Throwable) {
            VLog.bootE("boot", t)
        }
    }

    /** 0.2.22: buat folder eksternal meniru instalasi Android (Android/{data,obb,media}/<pkg>). */
    fun prepareExternal(pkg: String) {
        try {
            val ext = android.os.Environment.getExternalStorageDirectory()
            val cand = listOf(
                "Android/data/$pkg",
                "Android/data/$pkg/files",
                "Android/data/$pkg/cache",
                "Android/data/$pkg/no_backup",
                "Android/obb/$pkg",
                "Android/media/$pkg"
            )
            val res = ArrayList<String>()
            for (rel in cand) {
                val f = File(ext, rel)
                val ok = try { f.mkdirs() || f.exists() } catch (t: Throwable) { false }
                res.add("$rel=${if (ok) "OK" else "GAGAL"}")
            }
            val state = try { android.os.Environment.getExternalStorageState() } catch (t: Throwable) { "?" }
            VLog.bootOnly("ext", "prepareExternal $pkg (base=${ext.absolutePath} state=$state): ${res.joinToString(" ")}")
        } catch (t: Throwable) {
            VLog.bootE("ext", t)
        }
    }

    /** 0.2.22: laporkan avail bytes untuk path yang biasa dicek game. */
    fun describeStorage(pkg: String, ai: ApplicationInfo) {
        try {
            val ext = android.os.Environment.getExternalStorageDirectory()
            val paths = listOf(
                android.os.Environment.getDataDirectory(),
                ext,
                File(ext, "Android/data/$pkg"),
                File(ext, "Android/obb/$pkg"),
                File(ext, "Android/media/$pkg"),
                File(ai.dataDir),
                File(ai.sourceDir).parentFile
            )
            val sb = StringBuilder("StatFs $pkg:")
            for (p in paths) {
                val info = try {
                    val sf = android.os.StatFs(p.absolutePath)
                    "avail=${sf.availableBytes / (1024 * 1024)}MB"
                } catch (t: Throwable) { "err=${t.javaClass.simpleName}" }
                sb.append("\n   ${p.absolutePath} exists=${p.exists()} $info")
            }
            VLog.bootOnly("diag", sb.toString())
        } catch (t: Throwable) {
            VLog.bootE("diag", t)
        }
    }
}

    /**
     * 0.2.28: watchdog pasca-boot. Setelah ~3.5 detik, catat thread state semua thread
     * (nama + state + stack) ke boot.log. Berguna untuk mendiagnosa "Unity stuck" (CODM
     * blackscreen: log berhenti tepat setelah libunity.so dimuat).
     */
    fun threadWatchdog(pkg: String) {
        val th = Thread {
            try { Thread.sleep(3500) } catch (t: InterruptedException) { return@Thread }
            try {
                val all = Thread.getAllStackTraces()
                val sb = StringBuilder("threads[$pkg] ${all.size} thread:")
                for ((t, st) in all.sortedBy { it.key.name }) {
                    if (t == Thread.currentThread()) continue
                    val name = t.name
                    // Filter thread yang tidak relevan
                    if (name.startsWith("vspace-maps") || name.startsWith("Binder:") ||
                        name.startsWith("Signal Catcher") || name.startsWith("JDWP")) continue
                    sb.append("\n   [").append(name).append("] state=").append(t.state)
                    // Ambil 3 frame teratas saja
                    st.take(3).forEach { sb.append("\n      at ").append(it) }
                }
                VLog.bootOnly("thr", sb.toString())
            } catch (t: Throwable) { VLog.bootE("thr", t) }
        }
        th.isDaemon = true
        th.name = "vspace-thr-watchdog"
        th.start()
    }

    /**
     * 0.2.28: log identitas guest yang dilihat oleh kode Java (bukan yang dipatch di ApplicationInfo).
     * Mismatch antara `appInfo.uid` (yang kita force ke SYSTEM_UID=1000) dan `Process.myUid()`
     * (yang sebenarnya, mis. 10448) adalah kandidat kuat penyebab deteksi virtual (ACE di CODM).
     */
    fun logIdentity(pkg: String, ai: ApplicationInfo) {
        try {
            val appProc = try { Application.getProcessName() } catch (t: Throwable) { "?" }
            val mm = try { Process.myUid() } catch (t: Throwable) { -1 }
            val appUid = try { ai.uid } catch (t: Throwable) { -1 }
            val appPkgName = try { ai.packageName } catch (t: Throwable) { "?" }
            val appProcName = try { ai.processName } catch (t: Throwable) { "?" }
            val appDataDir = try { ai.dataDir } catch (t: Throwable) { "?" }
            val appSrc = try { ai.sourceDir } catch (t: Throwable) { "?" }
            VLog.bootOnly(
                "id",
                "guest=$pkg | myUid=$mm myPid=${Process.myPid()} myTid=${Process.myTid()} " +
                    "processName=$appProc | appInfo.uid=$appUid pkgName=$appPkgName " +
                    "procName=$appProcName dataDir=$appDataDir sourceDir=$appSrc"
            )
        } catch (t: Throwable) { VLog.bootE("id", t) }
    }
