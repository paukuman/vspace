package com.example.vspace

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView

/** Daftar app terinstall + kolom pencarian. Mengembalikan package name lewat extra "pkg". */
class PickerActivity : Activity() {

    private val all = ArrayList<Pair<String, String>>()   // label, pkg
    private val shown = ArrayList<Pair<String, String>>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        search = EditText(this).apply { hint = "Cari app / game..."; isSingleLine = true }
        val list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        list.adapter = adapter
        root.addView(search)
        root.addView(list)
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ ->
            val pkg = shown[pos].second
            setResult(RESULT_OK, Intent().putExtra("pkg", pkg))
            finish()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        Thread {
            val pm = packageManager
            val items = pm.getInstalledApplications(0)
                .filter { a ->
                    a.packageName != packageName &&
                        ((a.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            (a.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) &&
                        pm.getLaunchIntentForPackage(a.packageName) != null
                }
                .map { a -> Pair(a.loadLabel(pm).toString(), a.packageName) }
                .sortedBy { it.first.lowercase() }
            runOnUiThread {
                all.clear(); all.addAll(items)
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        shown.clear()
        shown.addAll(all.filter { q.isEmpty() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) })
        adapter.clear()
        adapter.addAll(shown.map { "${it.first}\n${it.second}" })
        adapter.notifyDataSetChanged()
    }
}
