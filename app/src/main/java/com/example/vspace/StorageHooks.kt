package com.example.vspace

import android.app.Application
import java.io.File

/**
 * 0.2.25: arahkan storage guest ke filesDir VSpace sendiri; tidak bergantung
 * /storage/emulated/0/Android/{data,obb,media}/<pkg>.
 *
 * Langkah:
 *  1) dumpContextImplFields() — nama field nyata di MIUI A10 (log ke boot.log)
 *  2) patch field ContextImpl kalau ada (di A10 sering NoSuchFieldException)
 *  3) INSTAL GuestContext ke guestApp.mBase — override getExternalFilesDir/CacheDir/ObbDir/dll
 *  4) testWrite() — verifikasi baca/tulis/hapus nyata ke path internal & /storage
 */
object StorageHooks {

    /**
     * 0.2.27: root folder untuk guest — prioritaskan folder external milik VSpace sendiri
     * (/storage/emulated/0/Android/data/com.example.vspace/files/guest/<pkg>) yang WRITABLE
     * oleh UID host + prefix /storage/... cocok harapan game. Fallback ke /data kalau gagal.
     */
    fun rootFor(pkg: String, guestApp: Application): File {
        val extBase = try { VApp.instance.getExternalFilesDir(null) } catch (t: Throwable) { null }
        if (extBase != null && extBase.canWrite()) {
            val root = File(extBase, "guest/$pkg")
            try { root.mkdirs() } catch (t: Throwable) { }
            VLog.bootOnly("stor", "rootFor $pkg -> external $root (canWrite=${root.canWrite()})")
            return root
        }
        val dataDir = guestApp.filesDir.parentFile ?: return File("/data")
        val root = File(dataDir, "ext/$pkg")
        try { root.mkdirs() } catch (t: Throwable) { }
        VLog.bootOnly("stor", "rootFor $pkg -> internal $root (extBase=${extBase?.absolutePath} canWrite=${extBase?.canWrite()})")
        return root
    }

    fun prepareAndPatch(pkg: String, guestApp: Application) {
        try { dumpContextImplFields() } catch (t: Throwable) { VLog.bootE("stor", t) }

        val ext: File = try {
            val dataDir = guestApp.filesDir.parentFile ?: return
            File(dataDir, "ext/$pkg").also { root ->
                for (sub in listOf("files", "cache", "obb", "media", "no_backup")) {
                    try { File(root, sub).mkdirs() } catch (t: Throwable) { }
                }
            }
        } catch (t: Throwable) { VLog.bootE("stor", t); return }
        VLog.bootOnly("stor", "prepareAndPatch $pkg root=${ext.absolutePath}")

        // (1) Coba patch field ContextImpl (kalau ada di versi Android ini)
        try {
            val ci = findContextImpl(guestApp)
            if (ci != null) {
                VLog.bootOnly("stor", "ContextImpl found: ${ci.javaClass.name}")
                setField(ci, "mExternalFilesDir", File(ext, "files"))
                setField(ci, "mExternalCacheDir", File(ext, "cache"))
                setField(ci, "mObbDir", File(ext, "obb"))
                setField(ci, "mExternalFilesDirs", arrayOf(File(ext, "files")))
                setField(ci, "mExternalCacheDirs", arrayOf(File(ext, "cache")))
                setField(ci, "mExternalMediaDirs", arrayOf(File(ext, "media")))
            } else {
                VLog.bootOnly("stor", "ContextImpl tidak ditemukan lewat mBase chain")
            }
        } catch (t: Throwable) { VLog.bootE("stor", t) }

        // (2) Instal GuestContext ke guestApp.mBase (UTAMA)
        try {
            val base = guestApp.baseContext
            if (base == null || base is GuestContext) {
                VLog.bootOnly("stor", "GuestContext tidak dipasang (base=${base?.javaClass?.name})")
            } else {
                val wrapped = GuestContext(base, pkg, ext)
                Refl.set(guestApp, "mBase", wrapped)
                VLog.bootOnly("stor", "GuestContext dipasang base=${base.javaClass.simpleName} root=${ext.absolutePath}")
            }
        } catch (t: Throwable) { VLog.bootE("stor", t) }

        // (3) Test tulis ke path internal (diharapkan OK)
        testWrite(File(ext, "files"), "internal_files_$pkg")

        // (4) Test tulis ke /storage (diagnostik: apakah mkdir/SE Linux benar-benar mengizinkan)
        try {
            val storExt = android.os.Environment.getExternalStorageDirectory()
            testWrite(File(storExt, "Android/data/$pkg/files"), "storage_data_files_$pkg")
            testWrite(File(storExt, "Android/obb/$pkg"), "storage_obb_$pkg")
            testWrite(File(storExt, "Android/media/$pkg"), "storage_media_$pkg")
        } catch (t: Throwable) { VLog.bootE("stor", t) }
    }

    fun dumpContextImplFields() {
        try {
            val cls = Class.forName("android.app.ContextImpl")
            val sb = StringBuilder("ContextImpl fields (${cls.declaredFields.size}):")
            for (f in cls.declaredFields) {
                sb.append("\n   ${java.lang.reflect.Modifier.toString(f.modifiers)} ${f.type.simpleName} ${f.name}")
            }
            VLog.bootOnly("stor", sb.toString())
        } catch (t: Throwable) { VLog.bootE("stor", t) }
    }

    private fun testWrite(dir: File, label: String) {
        try {
            if (!dir.exists()) {
                VLog.bootOnly("stor", "testWrite $label: TIDAK ADA ${dir.absolutePath}")
                return
            }
            val tmp = File(dir, ".vspace_test")
            val wrote = try { tmp.writeText("ok"); true } catch (t: Throwable) {
                VLog.bootOnly("stor", "testWrite $label: WRITE GAGAL ${t.javaClass.simpleName}: ${t.message}")
                false
            }
            if (wrote) {
                val read = try { tmp.readText() } catch (t: Throwable) { "?" }
                val del = try { tmp.delete() } catch (t: Throwable) { false }
                VLog.bootOnly("stor", "testWrite $label: OK read=$read del=$del path=${tmp.absolutePath}")
            }
        } catch (t: Throwable) { VLog.bootE("stor", t) }
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