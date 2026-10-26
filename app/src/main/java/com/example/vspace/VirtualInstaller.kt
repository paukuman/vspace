package com.example.vspace

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.net.Uri
import android.os.Build
import android.os.Process
import dalvik.system.DexClassLoader
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

class Guest(
    val pkg: String,
    val label: String,
    val apk: File,
    val libDir: File,
    val dataDir: File,
    val abi: String?,
    val activities: List<String>
) {
    /** ApplicationInfo guest yang diarahkan ke file & folder data di dalam host. */
    fun patchApp(ai: ApplicationInfo): ApplicationInfo {
        val n = ApplicationInfo(ai)
        n.sourceDir = apk.path
        n.publicSourceDir = apk.path
        n.nativeLibraryDir = libDir.path
        n.dataDir = dataDir.path
        n.deviceProtectedDataDir = dataDir.path
        n.uid = Process.myUid() // harus userId yang sama dgn proses host
        try { Refl.set(n, "credentialProtectedDataDir", dataDir.path) } catch (t: Throwable) { }
        try { if (abi != null) Refl.set(n, "primaryCpuAbi", abi) } catch (t: Throwable) { }
        return n
    }

    fun packageInfo(ctx: Context, flags: Int): PackageInfo? {
        val pm = ctx.packageManager
        val f = flags or PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
        val pi = (try { pm.getPackageArchiveInfo(apk.path, f) } catch (t: Throwable) { null })
            ?: pm.getPackageArchiveInfo(apk.path, PackageManager.GET_ACTIVITIES)
        if (pi != null) {
            val a = pi.applicationInfo
            if (a != null) pi.applicationInfo = patchApp(a)
        }
        return pi
    }

    fun activityInfo(ctx: Context, name: String): ActivityInfo? {
        val pi = ctx.packageManager.getPackageArchiveInfo(
            apk.path, PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
        ) ?: return null
        val ai = pi.activities?.firstOrNull { it.name == name } ?: return null
        val app = pi.applicationInfo ?: return null
        ai.applicationInfo = patchApp(app)
        return ai
    }
}

object VirtualInstaller {

    private fun root(ctx: Context) = File(ctx.filesDir, "virtual").apply { mkdirs() }

    fun install(ctx: Context, uri: Uri): Guest {
        val tmp = File(ctx.cacheDir, "import.apk")
        ctx.contentResolver.openInputStream(uri)!!.use { i ->
            tmp.outputStream().use { o -> i.copyTo(o) }
        }
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(tmp.absolutePath, PackageManager.GET_ACTIVITIES)
            ?: run { tmp.delete(); error("File bukan APK yang valid (catatan: XAPK/APKS/split belum didukung)") }
        val pkg = info.packageName

        val dir = File(root(ctx), pkg).apply { mkdirs() }
        val apk = File(dir, "base.apk")
        if (apk.exists()) { apk.setWritable(true); apk.delete() }
        tmp.copyTo(apk, overwrite = true)
        tmp.delete()

        // Native lib: pilih ABI yang cocok dengan bitness proses host (32/64-bit)
        val libDir = File(dir, "lib").apply { mkdirs() }
        libDir.listFiles()?.forEach { it.setWritable(true); it.delete() }
        val supported = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        var chosen: String? = null
        ZipFile(apk).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            val available = names.filter { it.startsWith("lib/") }.map { it.split('/')[1] }.distinct()
            chosen = supported.firstOrNull { it in available }
            if (available.isNotEmpty() && chosen == null) {
                apk.delete()
                error("ABI tidak cocok. Game punya $available, proses VSpace butuh ${supported.toList()}")
            }
            if (chosen != null) {
                z.entries().asSequence()
                    .filter { it.name.startsWith("lib/$chosen/") && it.name.endsWith(".so") }
                    .forEach { e ->
                        val out = File(libDir, File(e.name).name)
                        out.outputStream().use { o -> z.getInputStream(e).use { it.copyTo(o) } }
                        out.setReadOnly()
                    }
            }
        }
        File(dir, "abi.txt").writeText(chosen ?: "")
        apk.setReadOnly() // dex yang di-load wajib read-only di Android 14+

        return describe(ctx, dir, true)!!
    }

    fun list(ctx: Context): List<Guest> =
        root(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?.mapNotNull { describe(ctx, it, true) } ?: emptyList()

    fun get(ctx: Context, pkg: String, withLabel: Boolean = true): Guest? =
        describe(ctx, File(root(ctx), pkg), withLabel)

    fun delete(ctx: Context, g: Guest) {
        g.apk.parentFile?.deleteRecursively()
    }

    private fun describe(ctx: Context, dir: File, withLabel: Boolean): Guest? {
        val apk = File(dir, "base.apk")
        if (!apk.exists()) return null
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES) ?: return null
        val ai = info.applicationInfo ?: return null
        var label = info.packageName
        if (withLabel) {
            try {
                ai.sourceDir = apk.absolutePath
                ai.publicSourceDir = apk.absolutePath
                label = ai.loadLabel(pm).toString()
            } catch (t: Throwable) { }
        }
        val abi = File(dir, "abi.txt").takeIf { it.exists() }?.readText()?.ifEmpty { null }
        return Guest(
            pkg = info.packageName,
            label = label,
            apk = apk,
            libDir = File(dir, "lib"),
            dataDir = File(dir, "data").apply { mkdirs() },
            abi = abi,
            activities = info.activities?.map { it.name } ?: emptyList()
        )
    }

    /** Cari Activity MAIN/LAUNCHER dengan membaca AndroidManifest biner lewat AssetManager (hidden API). */
    fun launcher(g: Guest): String? {
        return try {
            val ctor = AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }
            val am = ctor.newInstance()
            val add = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            val cookie = add.invoke(am, g.apk.absolutePath) as Int
            if (cookie == 0) return null
            val p = am.openXmlResourceParser(cookie, "AndroidManifest.xml")
            val ns = "http://schemas.android.com/apk/res/android"
            var cur: String? = null
            var hasMain = false
            var hasLauncher = false
            var result: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "activity", "activity-alias" -> {
                            val target = p.getAttributeValue(ns, "targetActivity")
                            val name = p.getAttributeValue(ns, "name")
                            cur = full(g.pkg, target ?: name)
                            hasMain = false; hasLauncher = false
                        }
                        "intent-filter" -> { hasMain = false; hasLauncher = false }
                        "action" -> if (p.getAttributeValue(ns, "name") == "android.intent.action.MAIN") hasMain = true
                        "category" -> if (p.getAttributeValue(ns, "name") == "android.intent.category.LAUNCHER") hasLauncher = true
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "intent-filter") {
                    if (hasMain && hasLauncher && cur != null && result == null) result = cur
                    hasMain = false; hasLauncher = false
                }
                ev = p.next()
            }
            p.close()
            result
        } catch (t: Throwable) {
            VLog.e("launcher", t)
            null
        }
    }

    private fun full(pkg: String, n: String?): String? = when {
        n == null -> null
        n.startsWith(".") -> pkg + n
        n.contains('.') -> n
        else -> "$pkg.$n"
    }

    /** Tes sederhana (dari Tahap 1): bisakah kelas Activity dimuat lewat DexClassLoader? */
    fun testLoad(ctx: Context, g: Guest): String {
        return try {
            val odex = File(g.apk.parentFile, "odex").apply { mkdirs() }
            val loader = DexClassLoader(g.apk.absolutePath, odex.absolutePath, g.libDir.absolutePath, ctx.classLoader)
            val name = launcher(g) ?: g.activities.firstOrNull() ?: return "Tidak ada Activity di manifest"
            val clazz = loader.loadClass(name)
            "OK: berhasil load $name (super: ${clazz.superclass?.name})"
        } catch (t: Throwable) {
            "GAGAL: ${t::class.java.simpleName}: ${t.message}"
        }
    }
}
