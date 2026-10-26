package com.example.vspace

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Message
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

object Const {
    const val EXTRA_PKG = "vs_guest_pkg"
    const val EXTRA_CLS = "vs_guest_cls"
}

/**
 * TAHAP 2 (dijalankan di proses slot):
 *  1) Hook IPackageManager supaya framework menganggap paket guest "terinstall"
 *     (LoadedApk.initializeJavaContextClassLoader memanggil getPackageInfo).
 *  2) Hook ActivityThread.mH: saat Android meluncurkan Stub, ganti Intent + ActivityInfo
 *     di LaunchActivityItem menjadi milik Activity guest.
 */
object GuestHooks {

    fun install(app: Application) {
        hookPackageManager(app)
        hookHandler(app)
    }

    // ---------- 1. PackageManager ----------
    private fun hookPackageManager(app: Application) {
        val at = Class.forName("android.app.ActivityThread")
        val real = at.getDeclaredMethod("getPackageManager").apply { isAccessible = true }.invoke(null)
        val iface = Class.forName("android.content.pm.IPackageManager")

        val proxy = Proxy.newProxyInstance(app.classLoader, arrayOf(iface)) { _, m, args ->
            if (args != null && args.isNotEmpty() && args[0] is String) {
                val name = m.name
                if (name == "getPackageInfo" || name == "getApplicationInfo") {
                    val g = VirtualInstaller.get(app, args[0] as String, withLabel = false)
                    if (g != null) {
                        if (name == "getPackageInfo") {
                            val flags = ((args.getOrNull(1) as? Number)?.toLong() ?: 0L).toInt() and 0x7FFFFFFF
                            val pi = g.packageInfo(app, flags)
                            if (pi != null) return@newProxyInstance pi
                        } else {
                            val pi = g.packageInfo(app, 0)
                            if (pi?.applicationInfo != null) return@newProxyInstance pi.applicationInfo
                        }
                    }
                }
            }
            try {
                if (args == null) m.invoke(real) else m.invoke(real, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        Refl.field(at, "sPackageManager").set(null, proxy)
    }

    // ---------- 2. ActivityThread.mH ----------
    private fun hookHandler(app: Application) {
        val at = Class.forName("android.app.ActivityThread")
        val cur = at.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)!!
        val mH = Refl.get(cur, "mH") as Handler
        val execTx = try {
            Class.forName("android.app.ActivityThread\$H")
                .getDeclaredField("EXECUTE_TRANSACTION").apply { isAccessible = true }.getInt(null)
        } catch (t: Throwable) { 159 }

        Refl.set(mH, "mCallback", Handler.Callback { msg: Message ->
            try {
                if (msg.what == execTx) patchTransaction(app, msg.obj)
            } catch (t: Throwable) {
                VLog.e("patch", t)
            }
            false // lanjutkan penanganan normal
        })
    }

    private fun patchTransaction(app: Application, tx: Any?) {
        if (tx == null) return
        var c: Class<*>? = tx.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = f.get(tx) ?: continue
                if (v is List<*>) {
                    v.forEach { item ->
                        if (item != null && item.javaClass.name.endsWith("LaunchActivityItem")) patchLaunch(app, item)
                    }
                } else if (v.javaClass.name.endsWith("LaunchActivityItem")) {
                    patchLaunch(app, v)
                }
            }
            c = c.superclass
        }
    }

    private fun patchLaunch(app: Application, item: Any) {
        val intent = Refl.get(item, "mIntent") as? Intent ?: return
        val gp = intent.getStringExtra(Const.EXTRA_PKG) ?: return
        val gc = intent.getStringExtra(Const.EXTRA_CLS) ?: return
        val g = VirtualInstaller.get(app, gp, withLabel = false)
        if (g == null) { VLog.w("patch", "guest $gp tidak ditemukan"); return }
        val info = g.activityInfo(app, gc)
        if (info == null) { VLog.w("patch", "ActivityInfo $gc tidak ditemukan"); return }

        val ni = Intent(intent)
        ni.removeExtra(Const.EXTRA_PKG)
        ni.removeExtra(Const.EXTRA_CLS)
        ni.component = ComponentName(gp, gc)

        Refl.set(item, "mInfo", info)
        Refl.set(item, "mIntent", ni)
        VLog.w("patch", "Stub diganti -> $gp/$gc")
    }
}
