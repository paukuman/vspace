package com.example.vspace

import android.app.Activity
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
    private val REQ_PICK = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        scroll.addView(container, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        container.addView(TextView(this).apply { text = "VSpace - Tahap 1"; textSize = 22f })

        container.addView(Button(this).apply {
            text = "Pilih APK untuk di-clone"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(i, REQ_PICK)
            }
        })

        log = TextView(this).apply { setPadding(0, 16, 0, 16) }
        container.addView(log)

        VirtualInstaller.list(this).forEach { g ->
            container.addView(TextView(this).apply {
                text = "${g.label}\n${g.pkg}\nActivity: ${g.activities.size} | lib: ${g.libDir.list()?.size ?: 0} file .so"
                setPadding(0, 24, 0, 0)
            })
            container.addView(Button(this).apply {
                text = "Tes load kelas"
                setOnClickListener { log.text = VirtualInstaller.testLoad(this@MainActivity, g) }
            })
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        log.text = "Menyalin & memparse APK..."
        Thread {
            val msg = try {
                val g = VirtualInstaller.install(this, uri)
                "Berhasil: ${g.label} (${g.pkg})"
            } catch (t: Throwable) {
                "Gagal: ${t.message}"
            }
            runOnUiThread { refresh(); log.text = msg }
        }.start()
    }
}
