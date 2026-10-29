package com.example.vspace

import android.app.Application
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
        if (failed.isNotEmpty()) VLog.w("svc", "hook gagal: $failed")
    }

    private fun isGuest(app: Application, s: String): Boolean =
        guestCache.getOrPut(s) { VirtualInstaller.exists(app, s) }

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
                throw e.targetException
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
    }

    private fun hookActivityManager(app: Application) {
        val am = Class.forName("android.app.ActivityManager")
        val singleton = Refl.field(am, "IActivityManagerSingleton").get(null)!!
        val getM = Class.forName("android.util.Singleton").getDeclaredMethod("get").apply { isAccessible = true }
        val real = getM.invoke(singleton)
        val iface = Class.forName("android.app.IActivityManager")
        val proxy = Proxy.newProxyInstance(app.classLoader, arrayOf(iface)) { _, m, args ->
            try {
                rewrite(app, "am.${m.name}", args)
                if (args == null) m.invoke(real) else m.invoke(real, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        Refl.set(singleton, "mInstance", proxy)
    }
}
