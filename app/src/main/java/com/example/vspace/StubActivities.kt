package com.example.vspace

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Kalau stub ini benar-benar tampil, berarti hook GAGAL mengganti Activity. */
open class StubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Hook belum berhasil mengganti Activity.\n\nLog:\n" + VLog.read()
            setPadding(32, 64, 32, 32)
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }
}

class Stub0 : StubActivity()
class Stub1 : StubActivity()
class Stub2 : StubActivity()
