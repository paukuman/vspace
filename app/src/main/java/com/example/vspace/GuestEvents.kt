package com.example.vspace

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

/**
 * 0.2.30: hook untuk diagnosa "diam-diam mati" tanpa adb.
 *
 * Dari log 0.2.29:
 *  - 8 Ball Pool exit lewat ErrorDialog$2.onDismiss → System.exit(0). Kita tidak tahu
 *    pesan dialog yang tampil (kemungkinan "detected virtual environment"/storage).
 *  - CODM berhenti setelah Activity.mBase dipatch, tanpa exception, tanpa thread dump
 *    lanjutan — hanya satu sampel 3.5 detik pasca boot.
 *
 * Yang dipasang:
 *  1) UncaughtExceptionHandler default → catat stack ke boot.log (jaring pengaman MainLoopGuard
 *     tetap aktif untuk GMS; handler ini hanya mencatat, tidak mencegah).
 *  2) Shutdown hook → jejak stack saat Runtime/System.exit dipanggil (siapa yang manggil).
 *  3) Application.ActivityLifecycleCallbacks → urutan Activity (create/start/resume/pause/
 *     stop/destroy) ke boot.log. Kalau process mati setelah "resume X", kita tahu persis
 *     Activity mana yang terakhir aktif; kalau stuck, kita lihat Activity yang tidak pernah
 *     "resume" setelah "create".
 */
object GuestEvents {
    private val installed = HashSet<String>()

    fun install(app: Application, pkg: String) {
        if (!installed.add(pkg)) return

        try {
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    VLog.bootOnly(
                        "die",
                        "uncaught[$pkg] thread=${t.name}: ${e.javaClass.name}: ${e.message}\n" +
                            Log.getStackTraceString(e)
                    )
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) { VLog.bootE("die", t) }

        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    // Stack trace buatan sendiri: siapa yang memicu shutdown.
                    val trace = Log.getStackTraceString(Throwable("shutdown-trigger"))
                    VLog.bootOnly("exit", "shutdown hook[$pkg] dipanggil\n$trace")
                } catch (_: Throwable) { }
            })
        } catch (t: Throwable) { VLog.bootE("exit", t) }

        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, s: Bundle?) { VLog.bootOnly("act", "create ${a.javaClass.name}") }
                override fun onActivityStarted(a: Activity) { VLog.bootOnly("act", "start ${a.javaClass.name}") }
                override fun onActivityResumed(a: Activity) { VLog.bootOnly("act", "resume ${a.javaClass.name}") }
                override fun onActivityPaused(a: Activity) { VLog.bootOnly("act", "pause ${a.javaClass.name}") }
                override fun onActivityStopped(a: Activity) { VLog.bootOnly("act", "stop ${a.javaClass.name}") }
                override fun onActivitySaveInstanceState(a: Activity, s: Bundle) { }
                override fun onActivityDestroyed(a: Activity) { VLog.bootOnly("act", "destroy ${a.javaClass.name}") }
            })
        } catch (t: Throwable) { VLog.bootE("act", t) }
    }
}