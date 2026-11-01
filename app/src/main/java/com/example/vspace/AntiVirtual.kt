package com.example.vspace

import android.app.Application

/**
 * Anti-deteksi virtualisasi.
 *
 * Log 0.2.31 mengungkap dialog 8 Ball Pool:
 *   "8 Ball Pool detected this app is in use in a virtualized environment..."
 *
 * Signal publik yang biasa dipakai game (Miniclip/anti-cheat) untuk mendeteksi virtualisasi:
 *  1) Application.getProcessName() / ActivityThread.currentProcessName() = "com.example.vspace:p0"
 *     (bukannya pkg guest).  → di-fix di sini.
 *  2) PackageManager bisa resolve "com.example.vspace" dari dalam guest. → di-fix di GuestHooks.
 *  3) getRunningAppProcesses() memuat "com.example.vspace:pN". → di-fix di ServiceHooks.
 *  4) /proc/self/cmdline berisi "com.example.vspace:p0" dan /proc/self/maps memuat
 *     /data/app/com.example.vspace-*/base.apk (BUTUH native hook — di luar scope Java).
 */
object AntiVirtual {
    @Volatile private var installed = false

    /**
     * true hanya setelah hideProcessName selesai. GuestHooks memakai flag ini untuk
     * menunda hide `getPackageInfo(hostPkg)` sampai framework selesai boot.
     */
    @Volatile var armed = false
        private set

    fun install(app: Application, pkg: String) {
        if (installed) return
        installed = true
        hideProcessName(pkg)
        armed = true
    }

    private fun hideProcessName(pkg: String) {
        // Path A: Application.sProcessName (static field, API 31+). Di Android 10 tidak ada.
        var done = false
        try {
            val f = Application::class.java.getDeclaredField("sProcessName").apply { isAccessible = true }
            val old = try { f.get(null) } catch (t: Throwable) { "?" }
            f.set(null, pkg)
            VLog.bootOnly("av", "Application.sProcessName $old -> $pkg")
            done = true
        } catch (t: NoSuchFieldException) {
            VLog.bootOnly("av", "Application.sProcessName tidak ada (SDK<31), fallback")
        } catch (t: Throwable) {
            VLog.bootOnly("av", "sProcessName: ${t.javaClass.simpleName}: ${t.message}")
        }

        // Path B: ActivityThread.currentProcessName() membaca instance field mProcessName.
        // Ini jalur yang dipakai Application.getProcessName() (API 28+) dan
        // ActivityThread.currentProcessName() statis. Di A10 inilah satu-satunya yang ada.
        try {
            val at = Class.forName("android.app.ActivityThread")
            val cur = at.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            if (cur != null) {
                val f = at.getDeclaredField("mProcessName").apply { isAccessible = true }
                val old = try { f.get(cur) } catch (t: Throwable) { "?" }
                f.set(cur, pkg)
                VLog.bootOnly("av", "ActivityThread.mProcessName $old -> $pkg")
                done = true
            }
        } catch (t: Throwable) {
            VLog.bootOnly("av", "ActivityThread.mProcessName gagal: ${t.javaClass.simpleName}: ${t.message}")
        }

        // Path C: Application.setProcessName(String) (static, API 31+).
        try {
            val m = Application::class.java.getDeclaredMethod("setProcessName", String::class.java)
            m.invoke(null, pkg)
            VLog.bootOnly("av", "Application.setProcessName -> $pkg")
            done = true
        } catch (t: NoSuchMethodException) { /* A10: tidak ada */ }
        catch (t: Throwable) { VLog.bootOnly("av", "setProcessName: ${t.message}") }

        if (!done) VLog.bootOnly("av", "SEMUA path hideProcessName gagal")
    }
}