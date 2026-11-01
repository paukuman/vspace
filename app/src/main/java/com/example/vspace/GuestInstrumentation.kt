package com.example.vspace

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import java.io.File

/**
 * 0.2.26: Instrumentation wrapper — patch Activity.mBase ke GuestContext.
 *
 * Application context saja tidak cukup: Activity dibuat SETELAH Application.onCreate dan
 * punya ContextImpl sendiri. Activity.getExternalFilesDir() mengembalikan
 * /storage/Android/data/<pkg> (EACCES tulis) → 8BP "storage 20MB" & CODM blackscreen
 * setelah izin storage. Titik patch terbaik = Instrumentation.callActivityOnCreate
 * (dipanggil ActivityThread tepat sebelum Activity.onCreate).
 */
class GuestInstrumentation(
    private val real: Instrumentation,
    private val guestPkg: String,
    private val root: File
) : Instrumentation() {

    private fun patch(activity: Activity?) {
        if (activity == null) return
        try {
            val cur = activity.baseContext ?: return
            if (cur is GuestContext) return
            val wrapped = GuestContext(cur, guestPkg, root)
            Refl.set(activity, "mBase", wrapped)
            VLog.bootOnly("stor", "Activity.mBase dipatch: ${activity.javaClass.name} (base=${cur.javaClass.simpleName})")
        } catch (t: Throwable) {
            VLog.bootOnly("stor", "Activity.mBase patch gagal: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun getContext(): Context = real.context
    override fun callApplicationOnCreate(app: Application) { real.callApplicationOnCreate(app) }
    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity =
        real.newActivity(cl, className, intent)

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        patch(activity); real.callActivityOnCreate(activity, icicle)
    }
    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?, persistentState: PersistableBundle?) {
        patch(activity); real.callActivityOnCreate(activity, icicle, persistentState)
    }
    override fun callActivityOnRestoreInstanceState(activity: Activity, savedInstanceState: Bundle?) {
        patch(activity); real.callActivityOnRestoreInstanceState(activity, savedInstanceState)
    }
    override fun callActivityOnRestoreInstanceState(activity: Activity, savedInstanceState: Bundle, persistentState: PersistableBundle) {
        patch(activity); real.callActivityOnRestoreInstanceState(activity, savedInstanceState, persistentState)
    }
    override fun callActivityOnNewIntent(activity: Activity, intent: Intent) {
        patch(activity); real.callActivityOnNewIntent(activity, intent)
    }
    override fun callActivityOnStart(activity: Activity) { patch(activity); real.callActivityOnStart(activity) }
    override fun callActivityOnRestart(activity: Activity) { patch(activity); real.callActivityOnRestart(activity) }
    override fun callActivityOnResume(activity: Activity) { patch(activity); real.callActivityOnResume(activity) }
    override fun callActivityOnStop(activity: Activity) { patch(activity); real.callActivityOnStop(activity) }
    override fun callActivityOnPause(activity: Activity) { patch(activity); real.callActivityOnPause(activity) }
    override fun callActivityOnDestroy(activity: Activity) { patch(activity); real.callActivityOnDestroy(activity) }
    override fun callActivityOnPostCreate(activity: Activity, icicle: Bundle?) {
        patch(activity); real.callActivityOnPostCreate(activity, icicle)
    }
    override fun callActivityOnSaveInstanceState(activity: Activity, outState: Bundle) {
        patch(activity); real.callActivityOnSaveInstanceState(activity, outState)
    }
    override fun callActivityOnSaveInstanceState(activity: Activity, outState: Bundle, outPersistentState: PersistableBundle) {
        patch(activity); real.callActivityOnSaveInstanceState(activity, outState, outPersistentState)
    }
}