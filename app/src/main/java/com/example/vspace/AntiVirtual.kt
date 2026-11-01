package com.example.vspace

import android.app.Application

/**
 * 0.2.32: anti-deteksi virtualisasi.
 *
 * Log 0.2.31 mengungkap pesan 8 Ball Pool:
 *   "8 Ball Pool detected this app is in use in a virtualized environment.
 *    To protect you, the app will close. Support REF: 6913 8975:6E76:ADNW34"
 *
 * Signal publik yang biasa dipakai game (Miniclip/anti-cheat) untuk mendeteksi virtualisasi:
 *  1) Application.getProcessName() = "com.example.vspace:p0" (bukannya pkg guest).
 *  2) PackageManager bisa resolve "com.example.vspace" dari dalam guest.
 *  3) /proc/self/maps berisi path /data/app/com.example.vspace-* (BUTUH native hook —
 *     tidak bisa dihilangkan dari Java; di luar scope 0.2.32).
 *
 * 0.2.32 mengatasi (1) dan (2). Kalau setelah ini 8BP masih mendeteksi, sisa signal
 * hampir pasti (3) → perlu strategi LD_PRELOAD / ptrace / lsm hook.
 */
object AntiVirtual {
    @Volatile private var installed = false

    fun install(app: Application, pkg: String) {
        if (installed) return
        installed = true
        hideProcessName(pkg)
    }

    private fun hideProcessName(pkg: String) {
        // Application.sProcessName — static field, dibaca oleh Application.getProcessName()
        // dan ActivityThread.currentProcessName().
        try {
            val f = Application::class.java.getDeclaredField("sProcessName").apply { isAccessible = true }
            val old = try { f.get(null) } catch (t: Throwable) { "?" }
            f.set(null, pkg)
            VLog.bootOnly("av", "Application.sProcessName $old -> $pkg")
        } catch (t: Throwable) {
            VLog.bootOnly("av", "sProcessName gagal: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}