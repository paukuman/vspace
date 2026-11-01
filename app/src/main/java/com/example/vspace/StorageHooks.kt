package com.example.vspace

import android.app.Application
import java.io.File

/**
 * 0.2.24: preset cache ContextImpl guest supaya getExternalFilesDir()/getExternalCacheDir()/
 * getObbDir() mengarah ke folder milik VSpace sendiri (filesDir/ext/<pkg>/...), bukan
 * /storage/emulated/0/Android/{data,obb,media}/<pkg>. Tidak butuh mkdir di /storage.
 * Semua game yang memakai API konteks akan melihat storage besar (/data bebas).
 */
object StorageHooks {
    fun prepareAndPatch(pkg: String, guestApp: Application) {
        val base = guestApp.filesDir.parentFile ?: return // /data/data/com.example.vspace
        val ext = File(base, "ext/$pkg")
        val files = File(ext, "files")
        val cache = File(ext, "cache")
        val obb = File(ext, "obb")
        val media = File(ext, "media")
        val noBackup = File(ext, "no_backup")
        val ok = try { files.mkdirs() || files.exists() } catch (t: Throwable) { false }
        try { cache.mkdirs() } catch (t: Throwable) { }
        try { obb.mkdirs() } catch (t: Throwable) { }
        try { media.mkdirs() } catch (t: Throwable) { }
        try { noBackup.mkdirs() } catch (t: Throwable) { }
        VLog.bootOnly("stor", "prepareAndPatch $pkg base=${ext.absolutePath} files=$ok")

        try {
            val ci = findContextImpl(guestApp)
            if (ci == null) {
                VLog.bootOnly("stor", "ContextImpl guest tidak ditemukan, patch dilewati")
                return
            }
            setField(ci, "mExternalFilesDir", files)
            setField(ci, "mExternalCacheDir", cache)
            setField(ci, "mObbDir", obb)
            setField(ci, "mExternalFilesDirs", arrayOf(files))
            setField(ci, "mExternalCacheDirs", arrayOf(cache))
            setField(ci, "mExternalMediaDirs", arrayOf(media))
            VLog.bootOnly("stor", "ContextImpl dipatch -> files=${files.absolutePath} cache=${cache.absolutePath} obb=${obb.absolutePath}")
        } catch (t: Throwable) {
            VLog.bootE("stor", t)
        }
    }

    private fun findContextImpl(app: Application): Any? {
        var c: Any? = app
        var i = 0
        while (c != null && i < 10) {
            if (c.javaClass.name == "android.app.ContextImpl") return c
            c = try { Refl.get(c, "mBase") } catch (t: Throwable) { null }
            i++
        }
        return null
    }

    private fun setField(obj: Any, name: String, value: Any?) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(obj, value)
        } catch (t: Throwable) {
            VLog.bootOnly("stor", "set $name gagal: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}