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
                override fun onActivityResumed(a: Activity) {
                    VLog.bootOnly("act", "resume ${a.javaClass.name}")
                    // 0.2.31: dump teks semua window (termasuk dialog) supaya kita lihat
                    // pesan ErrorDialog 8BP sebelum exit.
                    scheduleWindowDump(pkg, a.javaClass.simpleName, 5000L)
                    scheduleWindowDump(pkg, a.javaClass.simpleName, 10000L)
                }
                override fun onActivityPaused(a: Activity) { VLog.bootOnly("act", "pause ${a.javaClass.name}") }
                override fun onActivityStopped(a: Activity) { VLog.bootOnly("act", "stop ${a.javaClass.name}") }
                override fun onActivitySaveInstanceState(a: Activity, s: Bundle) { }
                override fun onActivityDestroyed(a: Activity) { VLog.bootOnly("act", "destroy ${a.javaClass.name}") }
            })
        } catch (t: Throwable) { VLog.bootE("act", t) }
    }

    // 0.2.31: jadwalkan dump teks window setelah delay (untuk tangkap dialog yang muncul telat).
    private fun scheduleWindowDump(pkg: String, who: String, delayMs: Long) {
        val th = Thread {
            try { Thread.sleep(delayMs) } catch (t: InterruptedException) { return@Thread }
            dumpWindowText("$pkg/$who+${delayMs}ms")
        }
        th.isDaemon = true
        th.name = "vspace-ui-dump-$delayMs"
        th.start()
    }

    /**
     * 0.2.31: baca WindowManagerGlobal.mViews (reflection), telusuri semua View tree,
     * ambil teks TextView + hint EditText + label Button. Untuk 8BP kita butuh tahu
     * pesan error dialog yang muncul sebelum exit(0).
     */
    private fun dumpWindowText(tag: String) {
        try {
            val wmgCls = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmgCls.getDeclaredMethod("getInstance").invoke(null) ?: return
            val f = wmgCls.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = f.get(inst) as? List<Any?> ?: return
            val sb = StringBuilder("windows[$tag]: ${views.size} root")
            var total = 0
            for ((i, v) in views.withIndex()) {
                if (v == null) continue
                val texts = ArrayList<String>()
                try { collectTexts(v as android.view.View, texts, 60) } catch (t: Throwable) { }
                total += texts.size
                if (texts.isEmpty()) continue
                sb.append("\n  window#$i (${v.javaClass.name}) texts=${texts.size}")
                texts.forEach { sb.append("\n    • ").append(it) }
            }
            if (total == 0) sb.append(" (tidak ada teks)")
            VLog.bootOnly("ui", sb.toString())
        } catch (t: Throwable) {
            VLog.bootE("ui", t)
        }
    }

    private fun collectTexts(v: android.view.View, out: MutableList<String>, max: Int) {
        if (out.size >= max) return
        try {
            when (v) {
                is android.widget.TextView -> {
                    val t = v.text?.toString()?.trim()
                    if (!t.isNullOrEmpty()) out.add(t.take(240))
                }
                is android.view.ViewGroup -> {
                    for (i in 0 until v.childCount) collectTexts(v.getChildAt(i), out, max)
                }
            }
        } catch (t: Throwable) { }
    }
}