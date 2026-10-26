package com.example.vspace

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipFile

/** TAHAP 1: "install" virtual = salin APK ke folder privat host, parse, ekstrak .so, tes load kelas. */
object VirtualInstaller {

    data class Guest(
        val pkg: String,
        val label: String,
        val apk: File,
        val libDir: File,
        val dataDir: File,
        val activities: List<String>
    )

    private fun root(ctx: Context) = File(ctx.filesDir, "virtual").apply { mkdirs() }

    fun install(ctx: Context, uri: Uri): Guest {
        val tmp = File(ctx.cacheDir, "import.apk")
        ctx.contentResolver.openInputStream(uri)!!.use { i ->
            tmp.outputStream().use { o -> i.copyTo(o) }
        }
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(tmp.absolutePath, PackageManager.GET_ACTIVITIES)
            ?: run { tmp.delete(); error("File bukan APK yang valid") }
        val pkg = info.packageName

        val dir = File(root(ctx), pkg).apply { mkdirs() }
        val apk = File(dir, "base.apk")
        if (apk.exists()) { apk.setWritable(true); apk.delete() }
        tmp.copyTo(apk, overwrite = true)
        tmp.delete()

        // Ekstrak native lib sesuai ABI device (penting untuk game Unity/Unreal)
        val libDir = File(dir, "lib").apply { mkdirs() }
        ZipFile(apk).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            val abi = Build.SUPPORTED_ABIS.firstOrNull { a -> names.any { it.startsWith("lib/$a/") } }
            if (abi != null) {
                z.entries().asSequence()
                    .filter { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
                    .forEach { e ->
                        File(libDir, File(e.name).name).outputStream().use { o ->
                            z.getInputStream(e).use { it.copyTo(o) }
                        }
                    }
            }
        }

        // Android 14+ mewajibkan file dex yang di-load bersifat read-only
        apk.setReadOnly()

        return describe(ctx, dir)!!
    }

    fun list(ctx: Context): List<Guest> =
        root(ctx).listFiles()?.filter { it.isDirectory }?.mapNotNull { describe(ctx, it) } ?: emptyList()

    private fun describe(ctx: Context, dir: File): Guest? {
        val apk = File(dir, "base.apk")
        if (!apk.exists()) return null
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = apk.absolutePath
        ai.publicSourceDir = apk.absolutePath
        return Guest(
            pkg = info.packageName,
            label = ai.loadLabel(pm).toString(),
            apk = apk,
            libDir = File(dir, "lib"),
            dataDir = File(dir, "data").apply { mkdirs() },
            activities = info.activities?.map { it.name } ?: emptyList()
        )
    }

    /** Tes: bisakah kelas Activity guest dimuat lewat DexClassLoader? */
    fun testLoad(ctx: Context, g: Guest): String {
        return try {
            val odex = File(g.apk.parentFile, "odex").apply { mkdirs() }
            val loader = DexClassLoader(g.apk.absolutePath, odex.absolutePath, g.libDir.absolutePath, ctx.classLoader)
            val name = g.activities.firstOrNull() ?: return "Tidak ada Activity di manifest"
            val clazz = loader.loadClass(name)
            "OK: berhasil load $name (super: ${clazz.superclass?.name})"
        } catch (t: Throwable) {
            "GAGAL: ${t::class.java.simpleName}: ${t.message}"
        }
    }
}
