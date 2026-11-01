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

    /**
     * 0.2.28/0.2.30: sampler thread pasca-boot.
     * Versi 0.2.28 hanya mengambil SATU sampel 3.5 detik pasca boot. CODM berhenti
     * (blackscreen) setelah libunity.so dimuat (± 5.6 detik), jadi satu sampel terlalu
     * dini. Versi ini mengambil 12 sampel tiap 5 detik (total 60 detik) supaya thread
     * yang stuck setelah Unity mulai bisa ditangkap.
     */
    /**
     * 0.2.31: kirim SIGQUIT (kill -3) ke proses sendiri kalau main thread stuck di
     * nativeLoad > threshold (dari log 0.2.30: main RUNNABLE di
     * Runtime.nativeLoad selama 46+ detik → blackscreen CODM).
     * SIGQUIT memicu ART men-dump native stack semua thread (via debuggerd) ke logcat
     * dengan tag "DEBUG". Ini satu-satunya cara melihat frame native yang hang tanpa adb.
     * Kirim sekali saja per proses.
     */
    @Volatile private var sigquitSent = false
    fun nativeLoadWatchdog(pkg: String, thresholdMs: Long = 15000L) {
        val th = Thread {
            val start = SystemClock.uptimeMillis()
            var streak = 0
            while (SystemClock.uptimeMillis() - start < 120_000L) {
                try { Thread.sleep(2000L) } catch (t: InterruptedException) { return@Thread }
                if (sigquitSent) return@Thread
                val mainStuck = mainInNativeLoad()
                if (mainStuck) {
                    streak++
                    VLog.bootOnly("sigquit", "main nativeLoad terdeteksi (streak=$streak, +${(SystemClock.uptimeMillis()-start)/1000}s)")
                    if (streak >= (thresholdMs / 2000L).toInt()) {
                        sigquitSent = true
                        val pid = android.os.Process.myPid()
                        VLog.bootOnly("sigquit", "kirim SIGQUIT ke pid=$pid (main stuck nativeLoad > ${thresholdMs}ms)")
                        try {
                            Runtime.getRuntime().exec(arrayOf("kill", "-3", pid.toString())).waitFor()
                        } catch (t: Throwable) { VLog.bootE("sigquit", t) }
                        return@Thread
                    }
                } else {
                    streak = 0
                }
            }
        }
        th.isDaemon = true
        th.name = "vspace-sigquit-watch"
        th.start()
    }

    private fun mainInNativeLoad(): Boolean {
        return try {
            val main = Thread.getAllStackTraces().keys.firstOrNull { it.name == "main" } ?: return false
            val st = main.stackTrace
            if (st.isEmpty()) return false
            val top = st.first()
            top.className == "java.lang.Runtime" && top.methodName.startsWith("nativeLoad")
        } catch (t: Throwable) { false }
    }

    @Volatile private var sampling = false
    fun threadSampler(pkg: String, intervalMs: Long = 5000L, samples: Int = 12) {
        if (sampling) return
        sampling = true
        val th = Thread {
            try { Thread.sleep(3500) } catch (t: InterruptedException) { return@Thread }
            for (i in 1..samples) {
                try {
                    val all = Thread.getAllStackTraces()
                    val sb = StringBuilder("threads#$i[$pkg] ${all.size} thread:")
                    val entries = all.entries.sortedBy { it.key.name }
                    for (entry in entries) {
                        val t: Thread = entry.key
                        val st: Array<StackTraceElement> = entry.value
                        if (t === Thread.currentThread()) continue
                        val name: String = t.name
                        if (name.startsWith("vspace-maps") || name.startsWith("vspace-thr") ||
                            name.startsWith("Binder:") || name.startsWith("Signal Catcher") ||
                            name.startsWith("JDWP")) continue
                        sb.append("\n   [").append(name).append("] state=").append(t.state.name)
                        for (frame in st.take(3)) {
                            sb.append("\n      at ").append(frame.toString())
                        }
                    }
                    VLog.bootOnly("thr", sb.toString())
                } catch (t: Throwable) { VLog.bootE("thr", t) }
                if (i < samples) {
                    try { Thread.sleep(intervalMs) } catch (t: InterruptedException) { return@Thread }
                }
            }
        }
        th.isDaemon = true
        th.name = "vspace-thr-sampler"
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
}