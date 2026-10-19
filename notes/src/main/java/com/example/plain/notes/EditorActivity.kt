package com.example.plain.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * メモの編集画面。入力した内容は自動で保存されます（保存ボタンはありません）。
 * 他のアプリの「共有」から文字を受け取って、新しいメモにすることもできます。
 */
class EditorActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
        private const val KEY_ID = "id"
    }

    private lateinit var id: String
    private lateinit var edit: EditText
    private lateinit var bar: TopBar
    private var deleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)

        id = savedInstanceState?.getString(KEY_ID) ?: intent.getStringExtra(EXTRA_ID) ?: NoteStore.newId()
        val existing = NoteStore.load(this, id)
        val initial = when {
            existing != null -> existing.text
            intent.action == Intent.ACTION_SEND -> listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            ).filter { it.isNotBlank() }.joinToString("\n")
            else -> ""
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
        }
        root.padForSystemBars()

        bar = TopBar(this)
        bar.showBack(getString(R.string.back)) { saveAndFinish() }
        bar.titleView.textSize = 17f
        bar.action(getString(R.string.share)) { share() }
        bar.action(getString(R.string.delete)) { confirmDelete() }
        root.addView(bar)

        edit = EditText(this).apply {
            setText(initial)
            background = null
            gravity = Gravity.TOP or Gravity.START
            textSize = 17f
            setTextColor(fg())
            setLineSpacing(0f, 1.25f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.note_hint)
            setPadding(dp(20), dp(8), dp(20), dp(40))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = updateTitle()
            })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(edit, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        updateTitle()
        bar.setSubtitle(existing?.let {
            getString(R.string.edited, DateUtils.getRelativeTimeSpanString(it.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        })

        if (existing == null) {
            edit.requestFocus()
            edit.setSelection(edit.text.length)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { saveAndFinish() }
        }
    }

    @Deprecated("Android 12 以前の「戻る」")
    override fun onBackPressed() {
        saveAndFinish()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ID, id)
    }

    private fun save() {
        if (!deleted) NoteStore.save(this, id, edit.text.toString())
    }

    private fun saveAndFinish() {
        save()
        finish()
    }

    private fun updateTitle() {
        val n = edit.text.length
        bar.titleView.text = resources.getQuantityString(R.plurals.chars, n, n)
    }

    private fun share() {
        val text = edit.text.toString()
        if (text.isBlank()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_this)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleted = true
                NoteStore.delete(this, id)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
