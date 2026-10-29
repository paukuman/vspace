package com.example.vspace

import android.app.Application
import android.content.Context
import org.lsposed.hiddenapibypass.HiddenApiBypass

class VApp : Application() {
    companion object {
        lateinit var instance: VApp
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (t: Throwable) {
            VLog.e("bypass", t)
        }
        // Hook hanya dipasang di proses slot (":p0", ":p1", ":p2"), bukan di proses UI.
        val proc = try { Application.getProcessName() } catch (t: Throwable) { "" }
        if (proc.contains(":p")) {
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
                VLog.w("hook", "v$ver terpasang di $proc (SDK ${android.os.Build.VERSION.SDK_INT})")
            } catch (t: Throwable) {
                VLog.e("hook", t)
            }
        }
    }
}
