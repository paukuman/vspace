package com.example.vspace

import android.app.Application
import android.content.Intent
import android.os.IBinder
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Bungkus service sistem: argumen String yang berisi package GUEST diganti package HOST
 * (sistem memeriksa bahwa package milik UID pemanggil).
 */
object ServiceHooks {

    private val guestCache = ConcurrentHashMap<String, Boolean>()
    private val seenTags: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val errSeen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val hooked = HashSet<String>()

    // Service yang sengaja TIDAK dibungkus otomatis (punya hook sendiri / berisiko performa)
    private val SKIP = setOf("package", "activity", "activity_task", "window", "input", "display", "media.camera")

    private val SERVICES = listOf(
        "notification" to "android.app.INotificationManager",
        "alarm" to "android.app.IAlarmManager",
        "jobscheduler" to "android.app.job.IJobScheduler",
        "appops" to "com.android.internal.app.IAppOpsService",
        "wifi" to "android.net.wifi.IWifiManager",
        "connectivity" to "android.net.IConnectivityManager",
        "phone" to "com.android.internal.telephony.ITelephony",
        "audio" to "android.media.IAudioService",
        "usagestats" to "android.app.usage.IUsageStatsManager",
        "location" to "android.location.ILocationManager",
        "media_session" to "android.media.session.ISessionManager",
        "account" to "android.accounts.IAccountManager",
        "shortcut" to "android.content.pm.IShortcutService",
        "launcherapps" to "android.content.pm.ILauncherApps",
        "telecom" to "com.android.internal.telecom.ITelecomService",
        "isub" to "com.android.internal.telephony.ISub",
        "bluetooth_manager" to "android.bluetooth.IBluetoothManager",
        "clipboard" to "android.content.IClipboard",
        "device_policy" to "android.app.admin.IDevicePolicyManager",
        "role" to "android.app.role.IRoleManager",
        "carrier_config" to "com.android.internal.telephony.ICarrierConfigLoader",
        "power" to "android.os.IPowerManager",
        "user" to "android.os.IUserManager",
        "storagestats" to "android.app.usage.IStorageStatsManager",
        "appwidget" to "com.android.internal.appwidget.IAppWidgetService"
    )

    fun install(app: Application) {
        val failed = ArrayList<String>()
        for ((name, iface) in SERVICES) {
            try {
                hookService(app, name, iface)
            } catch (t: Throwable) {
                failed.add("$name(${t.javaClass.simpleName})")
            }
        }
        try {
            hookActivityManager(app)
        } catch (t: Throwable) {
            failed.add("activity(${t.javaClass.simpleName}: ${t.message})")
        }
        try {
            hookAuto(app)
        } catch (t: Throwable) {
            failed.add("auto(${t.javaClass.simpleName}: ${t.message})")
        }
        if (failed.isNotEmpty()) VLog.w("svc", "hook gagal: $failed")
    }

    private fun isGuest(app: Application, s: String): Boolean =
        guestCache.getOrPut(s) { VirtualInstaller.exists(app, s) }

    /** Bind dari guest ke Google Play Services: GMS menolak package guest -> gagalkan saja agar tidak crash. */
    private fun shouldBlockGmsBind(app: Application, args: Array<*>): Boolean {
        var fromGuest = false
        var gms = false
        for (a in args) {
            if (a is String && a.length in 3..200 && a.contains('.') && !a.contains('/') && isGuest(app, a)) fromGuest = true
            if (a is Intent) {
                val pkg = a.component?.packageName ?: a.getPackage()
                if (pkg == "com.google.android.gms" || a.action?.startsWith("com.google.android.gms.") == true) gms = true
            }
        }
        // Di proses slot semua kode adalah guest. callingPackage berisi package HOST (trik uid=SYSTEM_UID),
        // jadi jangan bergantung pada fromGuest.
        return gms
    }

    fun rewrite(app: Application, tag: String, args: Array<*>?) {
        if (args == null) return
        @Suppress("UNCHECKED_CAST")
        val a = args as Array<Any?>
        for (i in a.indices) {
            val s = a[i]
            if (s is String && s.length in 3..200 && s.contains('.') && !s.contains(' ') &&
                !s.contains('/') && s != app.packageName && isGuest(app, s)
            ) {
                a[i] = app.packageName
                if (seenTags.add(tag)) VLog.w("svc", "$tag: $s -> ${app.packageName}")
            } else if (s is android.content.ComponentName && s.packageName != app.packageName && isGuest(app, s.packageName)) {
                // 0.2.18: argumen ComponentName (mis. appwidget.getAppWidgetIds) dicek system_server terhadap uid pemanggil
                a[i] = android.content.ComponentName(app.packageName, s.className)
                if (seenTags.add(tag)) VLog.w("svc", "$tag: ComponentName ${s.packageName} -> ${app.packageName}")
            }
        }
    }

    private fun hookService(app: Application, name: String, ifaceName: String) {
        val sm = Class.forName("android.os.ServiceManager")
        val realBinder = sm.getDeclaredMethod("getService", String::class.java).invoke(null, name) as? IBinder ?: return
        val iface = Class.forName(ifaceName)
        val stub = Class.forName("$ifaceName\$Stub")
        val real = stub.getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, realBinder)

        val ifaceProxy = Proxy.newProxyInstance(app.classLoader, arrayOf(iface)) { _, m, args ->
            try {
                rewrite(app, "$name.${m.name}", args)
                if (args == null) m.invoke(real) else m.invoke(real, *args)
            } catch (e: InvocationTargetException) {
                val te = e.targetException
                // 0.2.17: catat exception asli dari service sistem (sekali per service.method:tipe, maks 40)
                val key = "$name.${m.name}:${te.javaClass.simpleName}"
                if (errSeen.size < 40 && errSeen.add(key)) VLog.w("svc-err", "$name.${m.name} -> ${te.javaClass.name}: ${te.message}")
                throw te
            }
        }
        val binderProxy = Proxy.newProxyInstance(app.classLoader, arrayOf(IBinder::class.java)) { _, m, args ->
            if (m.name == "queryLocalInterface") {
                ifaceProxy
            } else {
                try {
                    if (args == null) m.invoke(realBinder) else m.invoke(realBinder, *args)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        val cache = Refl.field(sm, "sCache").get(null) as MutableMap<String, IBinder>
        cache[name] = binderProxy as IBinder
        hooked.add(name)
    }

    /** Bungkus SEMUA service Java yang terdaftar: kelas interface dicari dari interfaceDescriptor binder-nya. */
    private fun hookAuto(app: Application) {
        val sm = Class.forName("android.os.ServiceManager")
        @Suppress("UNCHECKED_CAST")
        val names = (sm.getDeclaredMethod("listServices").invoke(null) as? Array<String>) ?: return
        val getService = sm.getDeclaredMethod("getService", String::class.java)
        var ok = 0
        for (name in names) {
            if (name in hooked || name in SKIP) continue
            try {
                val b = getService.invoke(null, name) as? IBinder ?: continue
                val desc = b.interfaceDescriptor
                if (desc.isNullOrEmpty() || !desc.contains('.')) continue
                hookService(app, name, desc)
                ok++
            } catch (t: Throwable) {
                // service native / kelas interface tidak ada: lewati
            }
        }
        VLog.w("svc", "auto-hook: $ok service tambahan (total ${hooked.size})")
    }

    private fun hookActivityManager(app: Application) {
        val am = Class.forName("android.app.ActivityManager")
        val singleton = Refl.field(am, "IActivityManagerSingleton").get(null)!!
        val getM = Class.forName("android.util.Singleton").getDeclaredMethod("get").apply { isAccessible = true }
        val real = getM.invoke(singleton)
        val iface = Class.forName("android.app.IActivityManager")
        val proxy = Proxy.newProxyInstance(app.classLoader, arrayOf(iface)) { _, m, args ->
            try {
                if (args != null && m.name.startsWith("bind") && shouldBlockGmsBind(app, args)) {
                    if (seenTags.add("am.bind.gms")) VLog.w("svc", "bind ke Google Play Services diblokir (belum didukung)")
                    return@newProxyInstance 0
                }
                rewrite(app, "am.${m.name}", args)
                if (args == null) m.invoke(real) else m.invoke(real, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        Refl.set(singleton, "mInstance", proxy)
    }
}
