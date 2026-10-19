package com.example.plain.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** メモの一覧 */
class MainActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private var all: List<Note> = emptyList()
    private var shown: List<Note> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false) // 最近使ったアプリ一覧にメモの中身を映さない

        val frame = FrameLayout(this).apply { setBackgroundColor(bg()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = TopBar(this)
        bar.titleView.text = getString(R.string.app_name)
        bar.action(getString(R.string.search)) { toggleSearch() }
        col.addView(bar)

        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            background = rounded(line(), 14)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
        }
        col.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), 0, dp(16), dp(8))
        })

        empty = label(getString(R.string.empty), 16f, color = sub()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(80), dp(32), 0)
            visibility = View.GONE
        }
        col.addView(empty, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        listView = ListView(this).apply {
            divider = null
            clipToPadding = false
            setPadding(0, 0, 0, dp(100))
            adapter = NoteAdapter()
            setOnItemClickListener { _, _, pos, _ -> open(shown[pos].id) }
            setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(shown[pos]); true }
        }
        col.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(col)

        val add = label("+", 34f, false, bg()).apply {
            gravity = Gravity.CENTER
            background = pressable(fg(), 32)
            elevation = dp(4).toFloat()
            contentDescription = getString(R.string.new_note)
            setOnClickListener { open(null) }
        }
        frame.addView(add, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(24), dp(24), dp(24), dp(24))
        })
        frame.padForSystemBars()
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        all = NoteStore.list(this)
        applyFilter()
    }

    private fun open(id: String?) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, id))
    }

    private fun toggleSearch() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (search.visibility == View.VISIBLE) {
            search.setText("")
            search.visibility = View.GONE
            imm.hideSoftInputFromWindow(search.windowToken, 0)
        } else {
            search.visibility = View.VISIBLE
            search.requestFocus()
            imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim()
        shown = if (q.isEmpty()) all else all.filter { it.text.contains(q, ignoreCase = true) }
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = getString(if (q.isEmpty()) R.string.empty else R.string.no_results)
    }

    private fun confirmDelete(n: Note) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, n.title.ifEmpty { getString(R.string.untitled) }))
            .setPositiveButton(R.string.delete) { _, _ ->
                NoteStore.delete(this, n.id)
                all = NoteStore.list(this)
                applyFilter()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class NoteAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                addView(label("", 17f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(label("", 14f, color = sub()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                addView(label("", 12f, color = sub()).apply { setPadding(0, dp(4), 0, 0) })
            }
            val n = shown[position]
            (v.getChildAt(0) as TextView).text = n.title.ifEmpty { getString(R.string.untitled) }
            val preview = v.getChildAt(1) as TextView
            preview.text = n.preview
            preview.visibility = if (n.preview.isEmpty()) View.GONE else View.VISIBLE
            (v.getChildAt(2) as TextView).text = DateUtils.getRelativeTimeSpanString(n.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            return v
        }
    }
}
