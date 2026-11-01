package com.example.vspace

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

class VApp : Application() {
    companion object {
        lateinit var instance: VApp
        @Volatile var nativeReady = false
        @JvmStatic external fun nativeInit()
        @JvmStatic external fun nativeSetGuestPkg(pkg: String)
        // 0.2.38f: pemuatan library native DIPINDAH dari init{} companion ke
        // sini, dan HANYA dipanggil di proses slot ":p*". Sebelumnya init{}
        // jalan di SEMUA proses termasuk proses UI main, dan hook
        // open/openat/fopen/dlopen di main merusak render surface (bukti user:
        // setelah hapus data pun UI tetap blank; screenshot putih, UI tree ada,
        // tanpa crash/tombstone). Di 0.2.37 tanpa library ini UI render normal.
        @Volatile private var nativeLoadTried = false
        @Synchronized
        fun ensureNativeLoaded() {
            if (nativeLoadTried) return
            nativeLoadTried = true
            try {
                System.loadLibrary("vspace_hooks")
                nativeInit()
                nativeReady = true
                android.util.Log.i("VSpace", "vspace_hooks loaded & init ok")
            } catch (t: Throwable) {
                nativeReady = false
                android.util.Log.e("VSpace", "vspace_hooks gagal load", t)
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
        // 0.2.27: pastikan folder /storage/emulated/0/Android/data/com.example.vspace/files dibuat
        // oleh sistem (dipanggil sekali di setiap proses, idempoten). Dipakai sebagai root storage guest.
        try {
            val ext = getExternalFilesDir(null)
            VLog.bootOnly("env", "getExternalFilesDir(null)=${ext?.absolutePath} exists=${ext?.exists()} canWrite=${ext?.canWrite()}")
        } catch (t: Throwable) { VLog.bootE("env", t) }
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (t: Throwable) {
            VLog.e("bypass", t)
        }
        // Hook hanya dipasang di proses slot (":p0", ":p1", ":p2"), bukan di proses UI.
        val proc = try { Application.getProcessName() } catch (t: Throwable) { "" }
        if (proc.contains(":p")) {
            // 0.2.38f: native hook hanya di proses guest, bukan di proses UI.
            ensureNativeLoaded()
            MainLoopGuard.install()
            installExitLogger()
            // Catat crash di proses guest ke file log (crash native/.so tidak tertangkap di sini)
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { th, e ->
                VLog.w("CRASH", "thread=${th.name}")
                VLog.e("CRASH", e)
                prev?.uncaughtException(th, e)
            }
            try {
                GuestHooks.install(this)
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (t: Throwable) { "?" }
                VLog.w("hook", "v$ver terpasang di $proc (SDK ${Build.VERSION.SDK_INT})")
            } catch (t: Throwable) {
                VLog.e("hook", t)
            }
        } else {
            logExitReasons()
        }
    }

    /** Guest yang memanggil System.exit (anti-tamper dll): catat jejak pemanggilnya. */
    private fun installExitLogger() {
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    for ((th, st) in Thread.getAllStackTraces()) {
                        val exiting = st.any {
                            (it.className == "java.lang.Runtime" || it.className == "java.lang.System") && it.methodName == "exit"
                        }
                        if (exiting) {
                            VLog.w("exit", "System.exit dipanggil dari thread ${th.name}:\n" +
                                st.take(14).joinToString("\n") { "   at $it" })
                        }
                    }
                } catch (t: Throwable) { }
            })
        } catch (t: Throwable) { }
    }

    /** Android 11+: alasan proses (termasuk :p0..:p2) mati menurut sistem. Hanya entri yang baru. */
    private fun logExitReasons() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val f = File(filesDir, "exit_ts")
            val last = try { f.readText().trim().toLong() } catch (t: Throwable) { 0L }
            var max = last
            for (e in am.getHistoricalProcessExitReasons(packageName, 0, 8)) {
                if (e.timestamp > last) {
                    VLog.w("exit-info", "${e.processName} reason=${e.reason} status=${e.status} desc=${e.description}")
                    if (e.timestamp > max) max = e.timestamp
                }
            }
            f.writeText(max.toString())
        } catch (t: Throwable) { }
    }
}
