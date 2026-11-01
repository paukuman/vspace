package com.example.vspace

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * 0.2.25: ContextWrapper yang mengarahkan semua external dir ke /data/.../ext/<pkg>/...
 * Tidak bergantung pada /storage/emulated/0/Android/{data,obb,media}/<pkg>.
 * Dipasang ke Application.mBase guest lewat refleksi (menggantikan ContextImpl asli).
 */
class GuestContext(base: Context, private val guestPkg: String, private val root: File) : ContextWrapper(base) {

    private fun filesDirOf(type: String?): File {
        val f = File(root, "files")
        try { f.mkdirs() } catch (t: Throwable) { }
        return if (type.isNullOrEmpty()) f else File(f, type).apply { try { mkdirs() } catch (t: Throwable) { } }
    }
    private fun cacheDirOf(): File = File(root, "cache").apply { try { mkdirs() } catch (t: Throwable) { } }
    private fun obbDirOf(): File = File(root, "obb").apply { try { mkdirs() } catch (t: Throwable) { } }
    private fun mediaDirOf(): File = File(root, "media").apply { try { mkdirs() } catch (t: Throwable) { } }

    override fun getExternalFilesDir(type: String?): File = filesDirOf(type)
    override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(filesDirOf(type))
    override fun getExternalCacheDir(): File = cacheDirOf()
    override fun getExternalCacheDirs(): Array<File> = arrayOf(cacheDirOf())
    override fun getObbDir(): File = obbDirOf()
    override fun getObbDirs(): Array<File> = arrayOf(obbDirOf())
    override fun getExternalMediaDirs(): Array<File> = arrayOf(mediaDirOf())
}