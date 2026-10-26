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
            try {
                GuestHooks.install(this)
                VLog.w("hook", "terpasang di $proc (SDK ${android.os.Build.VERSION.SDK_INT})")
            } catch (t: Throwable) {
                VLog.e("hook", t)
            }
        }
    }
}
