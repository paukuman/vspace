package com.example.vspace

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
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
 * Dijalankan di proses slot:
 *  1) Hook IPackageManager  -> paket guest dianggap "terinstall".
 *  2) Hook IActivityTaskManager.startActivity -> Activity guest membuka Activity guest lain lewat stub.
 *  3) Hook ActivityThread.mH -> Stub diganti Activity guest saat diluncurkan.
 */
object GuestHooks {

    private var dumped = 0
    private val booted = HashSet<String>()

    fun install(app: Application) {
        val proc = Application.getProcessName()
        val slot = proc.substringAfter(":p", "0").toIntOrNull() ?: 0
        try { hookPackageManager(app) } catch (t: Throwable) { VLog.e("hookPM", t) }
        try { hookActivityTaskManager(app, slot) } catch (t: Throwable) { VLog.e("hookATM", t) }
        try { ServiceHooks.install(app) } catch (t: Throwable) { VLog.e("hookSvc", t) }
        hookHandler(app)
    }

    /** Diagnostik: struktur kelas MIUI yang bikin crash di ViewRootImpl (hanya ada di HP Xiaomi). */
    private fun dumpMiui() {
        var lines = 0
        fun out(t: String) { if (lines++ < 90) VLog.w("miui", t.take(260)) }
        for (cn in listOf("android.view.ForceDarkHelper", "android.os.MiuiBinderProxy")) {
            val c = try { Class.forName(cn) } catch (t: Throwable) { out("$cn: tidak ada"); continue }
            out("== $cn extends ${c.superclass?.name}")
            c.declaredConstructors.forEach { out("C ${it.toGenericString()}") }
            c.declaredFields.forEach { f ->
                var v = ""
                if (Modifier.isStatic(f.modifiers)) {
                    v = try { f.isAccessible = true; " = " + f.get(null) } catch (t: Throwable) { "" }
                }
                out("F ${f.toGenericString()}$v")
            }
            val all = cn.endsWith("MiuiBinderProxy")
            c.declaredMethods.filter { all || Regex("(?i)regist|observer|dark|config|proxy").containsMatchIn(it.name) }
                .forEach { out("M ${it.toGenericString()}") }
        }
    }

    // ---------- 1. PackageManager ----------
    private fun hookPackageManager(app: Application) {
        val at = Class.forName("android.app.ActivityThread")
        val real = at.getDeclaredMethod("getPackageManager").apply { isAccessible = true }.invoke(null)
        val iface = Class.forName("android.content.pm.IPackageManager")

        val proxy = Proxy.newProxyInstance(app.classLoader, arrayOf(iface)) { _, m, args ->
            // Komponen milik guest: jangan sentuh PackageManager asli
            val first = args?.getOrNull(0)
            if (first is ComponentName && VirtualInstaller.exists(app, first.packageName)) {
                when (m.name) {
                    "setComponentEnabledSetting" -> return@newProxyInstance null
                    "getComponentEnabledSetting" -> return@newProxyInstance 0
                    "getActivityInfo", "getServiceInfo", "getReceiverInfo", "getProviderInfo" -> {
                        val g = VirtualInstaller.get(app, first.packageName, withLabel = false)
                        val ci = g?.componentInfo(app, m.name, first.className)
                        if (ci != null) return@newProxyInstance ci
                    }
                }
            }
            if (args != null && args.isNotEmpty() && args[0] is String) {
                val name = m.name
                val target = args[0] as String
                if ((name == "getPackageInfo" || name == "getApplicationInfo") && VirtualInstaller.exists(app, target)) {
                    val g = VirtualInstaller.get(app, target, withLabel = false)
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

    // ---------- 2. startActivity dari dalam guest ----------
    private fun hookActivityTaskManager(app: Application, slot: Int) {
        val atm = Class.forName("android.app.ActivityTaskManager")
        val singleton = Refl.field(atm, "IActivityTaskManagerSingleton").get(null)!!
        val getM = Class.forName("android.util.Singleton").getDeclaredMethod("get").apply { isAccessible = true }
        val real = getM.invoke(singleton)
        val iface = Class.forName("android.app.IActivityTaskManager")

        val proxy = Proxy.newProxyInstance(app.classLoader, arrayOf(iface)) { _, m, args ->
            try {
                if (args != null && m.name.startsWith("startActivity")) rewriteStart(app, slot, args)
                else ServiceHooks.rewrite(app, "atm.${m.name}", args)
            } catch (t: Throwable) {
                VLog.e("startActivity", t)
            }
            try {
                if (args == null) m.invoke(real) else m.invoke(real, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        Refl.set(singleton, "mInstance", proxy)
    }

    @Suppress("UNCHECKED_CAST")
    private fun rewriteStart(app: Application, slot: Int, argsIn: Array<*>) {
        val args = argsIn as Array<Any?>
        var pkgIdx = -1
        var intentIdx = -1
        for (i in args.indices) {
            if (pkgIdx < 0 && args[i] is String) pkgIdx = i
            if (intentIdx < 0 && args[i] is Intent) intentIdx = i
        }
        if (pkgIdx < 0 || intentIdx < 0) return
        val callingPkg = args[pkgIdx] as String
        if (!VirtualInstaller.exists(app, callingPkg)) return // pemanggil bukan guest

        args[pkgIdx] = app.packageName // sistem memeriksa package milik UID pemanggil
        val intent = args[intentIdx] as Intent
        val comp = intent.component
        if (comp != null && VirtualInstaller.exists(app, comp.packageName)) {
            val stub = Intent(intent)
            stub.setPackage(null)
            // Semua Activity guest memakai stub yang sama: flag ini membuat sistem salah mengirim ke instance lain
            stub.flags = stub.flags and (Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).inv()
            stub.component = ComponentName(app.packageName, "com.example.vspace.Stub$slot")
            stub.putExtra(Const.EXTRA_PKG, comp.packageName)
            stub.putExtra(Const.EXTRA_CLS, comp.className)
            args[intentIdx] = stub
            VLog.w("startActivity", "${comp.packageName}/${comp.className} -> Stub$slot")
        } else {
            VLog.w("startActivity", "tidak diubah: intent=$intent")
        }
    }

    // ---------- 3. ActivityThread.mH ----------
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
        var patched = false
        val seen = ArrayList<String>()
        var c: Class<*>? = tx.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = f.get(tx) ?: continue
                if (v is List<*>) {
                    v.forEach { item ->
                        if (item != null) {
                            seen.add(item.javaClass.simpleName)
                            if (item.javaClass.name.endsWith("LaunchActivityItem") && patchLaunch(app, item)) patched = true
                        }
                    }
                } else if (v.javaClass.name.endsWith("LaunchActivityItem")) {
                    seen.add(v.javaClass.simpleName)
                    if (patchLaunch(app, v)) patched = true
                }
            }
            c = c.superclass
        }
        if (!patched && dumped < 25) {
            dumped++
            VLog.w("tx", "transaksi tanpa patch, item=$seen")
        }
    }

    private fun patchLaunch(app: Application, item: Any): Boolean {
        val intent = Refl.get(item, "mIntent") as? Intent ?: return false
        val gp = intent.getStringExtra(Const.EXTRA_PKG)
        val gc = intent.getStringExtra(Const.EXTRA_CLS)
        if (gp == null || gc == null) {
            if (intent.component?.className?.startsWith("com.example.vspace.Stub") == true) {
                VLog.w("patch", "Stub diluncurkan TANPA extra guest: $intent")
            }
            return false
        }
        val g = VirtualInstaller.get(app, gp, withLabel = false)
        if (g == null) { VLog.w("patch", "guest $gp tidak ditemukan"); return false }
        val info = g.activityInfo(app, gc)
        if (info == null) { VLog.w("patch", "ActivityInfo $gc tidak ditemukan"); return false }

        // Buat Application guest + ContentProvider-nya SEBELUM Activity di-instantiate
        if (!booted.contains(g.pkg)) VLog.w("boot", "mulai ${g.pkg}")
        try {
            bootGuest(app, g, info.applicationInfo, item)
        } catch (t: Throwable) {
            booted.remove(g.pkg)
            VLog.e("boot", (t as? InvocationTargetException)?.targetException ?: t)
        }

        val ni = Intent(intent)
        ni.removeExtra(Const.EXTRA_PKG)
        ni.removeExtra(Const.EXTRA_CLS)
        ni.component = ComponentName(gp, gc)

        Refl.set(item, "mInfo", info)
        Refl.set(item, "mIntent", ni)
        val seInfo = try { Refl.get(info.applicationInfo, "seInfo") } catch (t: Throwable) { "?" }
        VLog.w(
            "patch",
            "Stub diganti -> $gp/$gc | apk=${g.apk.exists()} splits=${g.splits.size} " +
                "so=${g.libDir.list()?.size ?: 0} abi=${g.abi} | uid=${info.applicationInfo.uid} " +
                "proc=${info.applicationInfo.processName} seInfo=$seInfo"
        )
        return true
    }

    /** Meniru ActivityThread.handleBindApplication untuk paket guest (sekali per proses). */
    private fun bootGuest(app: Application, g: Guest, appInfo: ApplicationInfo, item: Any) {
        if (!booted.add(g.pkg)) return
        val atCls = Class.forName("android.app.ActivityThread")
        val at = atCls.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)!!
        val compatCls = Class.forName("android.content.res.CompatibilityInfo")
        val compat = (try { Refl.get(item, "mCompatInfo") } catch (t: Throwable) { null })
            ?: Refl.field(compatCls, "DEFAULT_COMPATIBILITY_INFO").get(null)

        val getNoCheck = atCls.getDeclaredMethod("getPackageInfoNoCheck", ApplicationInfo::class.java, compatCls)
            .apply { isAccessible = true }
        val loadedApk = getNoCheck.invoke(at, appInfo, compat)!!

        val make = loadedApk.javaClass.getDeclaredMethod(
            "makeApplication", Boolean::class.javaPrimitiveType, Instrumentation::class.java
        ).apply { isAccessible = true }
        val guestApp = make.invoke(loadedApk, false, null) as Application
        VLog.w("boot", "Application guest dibuat: ${guestApp.javaClass.name}")

        val providers = g.providers(app)
        if (providers.isNotEmpty()) {
            val inst = atCls.getDeclaredMethod("installContentProviders", Context::class.java, List::class.java)
                .apply { isAccessible = true }
            inst.invoke(at, guestApp, providers)
            VLog.w("boot", "ContentProvider dipasang: ${providers.size}")
        }

        val instrumentation = Refl.get(at, "mInstrumentation") as Instrumentation
        instrumentation.callApplicationOnCreate(guestApp)
        VLog.w("boot", "Application.onCreate selesai")
    }
}
