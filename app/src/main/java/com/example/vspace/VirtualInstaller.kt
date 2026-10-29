package com.example.vspace

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
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
    val splits: List<File>,
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
        if (splits.isNotEmpty()) {
            val arr = splits.map { it.path }.toTypedArray()
            n.splitSourceDirs = arr
            n.splitPublicSourceDirs = arr
            // Android 10 (LoadedApk.setupJitProfileSupport) membaca splitNames[i-1]; tidak boleh null
            n.splitNames = splits.map { it.name.removeSuffix(".apk").removePrefix("split_") }.toTypedArray()
            try { Refl.set(n, "splitFlags", IntArray(splits.size)) } catch (t: Throwable) { }
        }
        n.nativeLibraryDir = libDir.path
        // Field yang biasanya diisi PackageManager saat install (null kalau dibaca dari file APK).
        // Kode MIUI (ForceDarkHelper) meneruskan processName ke binder -> NPE bila null.
        n.processName = pkg
        try { Refl.set(n, "nativeLibraryRootDir", libDir.path) } catch (t: Throwable) { }
        try {
            val host = Refl.get(VApp.instance.applicationInfo, "seInfo") as? String
            Refl.set(n, "seInfo", host ?: "default:targetSdkVersion=28:complete")
        } catch (t: Throwable) { }
        n.dataDir = dataDir.path
        n.deviceProtectedDataDir = dataDir.path
        // Trik: ContextImpl memakai nama paket PROSES (host) sebagai opPackageName bila ApplicationInfo.uid == SYSTEM_UID
        // dan proses bukan system. Dengan begitu Settings/AppOps/ContentResolver tidak menolak package guest.
        // (userId 1000 = user 0, jadi hanya aman di user utama.)
        n.uid = Process.SYSTEM_UID
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

    /** ContentProvider guest untuk proses utama guest (meniru yang dilakukan handleBindApplication). */
    fun providers(ctx: Context): List<ProviderInfo> {
        val pi = ctx.packageManager.getPackageArchiveInfo(
            apk.path, PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
        ) ?: return emptyList()
        val app = pi.applicationInfo ?: return emptyList()
        val patched = patchApp(app)
        return (pi.providers ?: emptyArray())
            .filter { it.enabled && (it.processName == null || it.processName == pkg) }
            .sortedByDescending { it.initOrder }
            .onEach { it.applicationInfo = patched }
    }

    /** Jawaban untuk IPackageManager.getActivityInfo/getServiceInfo/getReceiverInfo/getProviderInfo. */
    fun componentInfo(ctx: Context, method: String, cls: String): Any? {
        val pi = ctx.packageManager.getPackageArchiveInfo(
            apk.path,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
        ) ?: return null
        val app = pi.applicationInfo ?: return null
        val arr: Array<out ComponentInfo>? = when (method) {
            "getActivityInfo" -> pi.activities
            "getReceiverInfo" -> pi.receivers
            "getServiceInfo" -> pi.services
            else -> pi.providers
        }
        val ci = arr?.firstOrNull { it.name == cls } ?: return null
        ci.applicationInfo = patchApp(app)
        return ci
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

    fun exists(ctx: Context, pkg: String): Boolean = File(File(root(ctx), pkg), "base.apk").exists()

    /** Dari file APK tunggal (file picker). */
    fun install(ctx: Context, uri: Uri): Guest {
        val tmp = File(ctx.cacheDir, "import.apk")
        ctx.contentResolver.openInputStream(uri)!!.use { i ->
            tmp.outputStream().use { o -> i.copyTo(o) }
        }
        return commit(ctx, tmp, emptyList())
    }

    /** Dari app yang sudah terinstall (base.apk + semua split). */
    fun installFromInstalled(ctx: Context, pkg: String): Guest {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        val tmp = File(ctx.cacheDir, "import.apk")
        File(ai.sourceDir).copyTo(tmp, overwrite = true)
        val splits = ai.splitSourceDirs?.map { File(it) } ?: emptyList()
        return commit(ctx, tmp, splits)
    }

    private fun commit(ctx: Context, baseTmp: File, splitSrc: List<File>): Guest {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(baseTmp.absolutePath, PackageManager.GET_ACTIVITIES)
            ?: run { baseTmp.delete(); error("File bukan APK yang valid (XAPK/APKS belum didukung)") }
        val pkg = info.packageName
        if (pkg == ctx.packageName) { baseTmp.delete(); error("Tidak bisa meng-clone VSpace sendiri") }

        val dir = File(root(ctx), pkg).apply { mkdirs() }
        val apk = File(dir, "base.apk")
        if (apk.exists()) { apk.setWritable(true); apk.delete() }
        baseTmp.copyTo(apk, overwrite = true)
        baseTmp.delete()

        val splitDir = File(dir, "splits")
        splitDir.listFiles()?.forEach { it.setWritable(true); it.delete() }
        splitDir.mkdirs()
        val splits = splitSrc.map { s -> File(splitDir, s.name).also { s.copyTo(it, overwrite = true) } }
        val allApks = listOf(apk) + splits

        // Native lib dari base + semua split, sesuai bitness proses host
        val libDir = File(dir, "lib").apply { mkdirs() }
        libDir.listFiles()?.forEach { it.setWritable(true); it.delete() }
        val supported = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val available = allApks.flatMap { f ->
            ZipFile(f).use { z ->
                z.entries().asSequence().map { it.name.split('/') }
                    .filter { it.size >= 3 && it[0] == "lib" }.map { it[1] }.toList()
            }
        }.distinct()
        val chosen = supported.firstOrNull { it in available }
        if (available.isNotEmpty() && chosen == null) {
            error("ABI tidak cocok. Game punya $available, proses VSpace butuh ${supported.toList()}")
        }
        if (chosen != null) {
            allApks.forEach { f ->
                ZipFile(f).use { z ->
                    z.entries().asSequence()
                        .filter { it.name.startsWith("lib/$chosen/") && it.name.endsWith(".so") }
                        .forEach { e ->
                            val out = File(libDir, File(e.name).name)
                            out.outputStream().use { o -> z.getInputStream(e).use { it.copyTo(o) } }
                            out.setReadOnly()
                        }
                }
            }
        }
        File(dir, "abi.txt").writeText(chosen ?: "")
        allApks.forEach { it.setReadOnly() } // dex wajib read-only di Android 14+

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
        val splits = File(dir, "splits").listFiles()?.filter { it.name.endsWith(".apk") }?.sortedBy { it.name }
            ?: emptyList()
        return Guest(
            pkg = info.packageName,
            label = label,
            apk = apk,
            splits = splits,
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
