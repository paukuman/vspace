package com.example.vspace

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var log: TextView
    private val reqPick = 1001
    private val reqInstalled = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        scroll.addView(container, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        container.removeAllViews()
        container.addView(TextView(this).apply { text = "VSpace - Tahap 2.5"; textSize = 22f })

        container.addView(btn("Pilih dari app terinstall") {
            startActivityForResult(Intent(this, PickerActivity::class.java), reqInstalled)
        })
        container.addView(btn("Pilih file APK") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, reqPick)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Lihat log") { log.text = VLog.read() })
        row.addView(btn("Hapus log") { VLog.clear(); log.text = "Log dihapus" })
        row.addView(btn("Salin log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("vspace-log", VLog.read(30000)))
            log.text = "Log disalin ke clipboard (tempel di chat)"
        })
        container.addView(row)

        log = TextView(this).apply { setPadding(0, 16, 0, 16); setTextIsSelectable(true) }
        container.addView(log)

        VirtualInstaller.list(this).forEachIndexed { index, g ->
            val slot = index % 3
            container.addView(TextView(this).apply {
                text = "${g.label}\n${g.pkg}\nActivity: ${g.activities.size} | ABI: ${g.abi ?: "-"} | " +
                    "lib: ${g.libDir.list()?.size ?: 0} file .so"
                setPadding(0, 24, 0, 0)
            })
            container.addView(btn("Jalankan (slot $slot)") { launch(g, slot) })
            container.addView(btn("Tes load kelas") { log.text = VirtualInstaller.testLoad(this, g) })
            container.addView(btn("Hapus") { VirtualInstaller.delete(this, g); refresh() })
        }
    }

    private fun launch(g: Guest, slot: Int) {
        val cls = VirtualInstaller.launcher(g) ?: g.activities.firstOrNull()
        if (cls == null) { log.text = "Tidak ada Activity untuk dijalankan"; return }
        VLog.w("launch", "${g.pkg}/$cls di slot $slot")
        val i = Intent()
            .setClassName(packageName, "com.example.vspace.Stub$slot")
            .putExtra(Const.EXTRA_PKG, g.pkg)
            .putExtra(Const.EXTRA_CLS, cls)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val job: (() -> Guest)? = when (requestCode) {
            reqPick -> data?.data?.let { uri -> { VirtualInstaller.install(this, uri) } }
            reqInstalled -> data?.getStringExtra("pkg")?.let { pkg -> { VirtualInstaller.installFromInstalled(this, pkg) } }
            else -> null
        }
        if (job == null) return
        log.text = "Menyalin & memparse APK (game besar butuh waktu)..."
        Thread {
            val msg = try {
                val g = job()
                "Berhasil: ${g.label} (${g.pkg}) | split: ${g.splits.size} | .so: ${g.libDir.list()?.size ?: 0}"
            } catch (t: Throwable) {
                "Gagal: ${t.message}"
            }
            runOnUiThread { refresh(); log.text = msg }
        }.start()
    }
}
