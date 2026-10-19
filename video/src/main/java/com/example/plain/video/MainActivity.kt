package com.example.plain.video

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

/** 動画の一覧 */
class MainActivity : Activity() {

    private lateinit var bar: TopBar
    private lateinit var listView: ListView
    private lateinit var message: TextView
    private lateinit var banner: TextView
    private var all: List<Video> = emptyList()
    private var shown: List<Video> = emptyList()
    private var folder: String? = null // null = すべて
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        folder = savedInstanceState?.getString(KEY_FOLDER)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
        }
        root.padForSystemBars()

        bar = TopBar(this)
        bar.titleView.text = getString(R.string.app_name)
        bar.action(getString(R.string.folders)) { chooseFolder() }
        root.addView(bar)

        banner = label(getString(R.string.partial_access), 14f, true, bg()).apply {
            background = pressable(fg(), 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            setOnClickListener { requestPermissions(Perms.needed(), REQ_PERM) }
        }
        root.addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), 0, dp(12), dp(8))
        })

        message = label("", 16f, color = sub()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(80), dp(32), dp(16))
            visibility = View.GONE
        }
        root.addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        listView = ListView(this).apply {
            divider = null
            adapter = VideoAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java).setData(shown[pos].uri)
                    .putExtra(PlayerActivity.EXTRA_TITLE, shown[pos].name))
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (!Perms.any(this)) requestPermissions(Perms.needed(), REQ_PERM)
    }

    override fun onResume() {
        super.onResume()
        if (Perms.any(this)) reload() else showDenied()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FOLDER, folder)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Perms.any(this)) reload() else showDenied()
    }

    private fun showDenied() {
        all = emptyList()
        applyFilter()
        message.visibility = View.VISIBLE
        message.text = getString(R.string.need_permission)
        message.setOnClickListener {
            if (shouldShowRequestPermissionRationale(Perms.needed()[0])) {
                requestPermissions(Perms.needed(), REQ_PERM)
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun reload() {
        banner.visibility = if (Perms.partial(this)) View.VISIBLE else View.GONE
        message.setOnClickListener(null)
        io.execute {
            val list = VideoRepo.query(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                all = list
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val f = folder
        shown = if (f == null) all else all.filter { it.bucketId == f }
        if (f != null && shown.isEmpty() && all.isNotEmpty()) {
            folder = null
            shown = all
        }
        bar.titleView.text = if (folder == null) getString(R.string.app_name) else shown.firstOrNull()?.bucket ?: getString(R.string.app_name)
        bar.setSubtitle(if (all.isEmpty()) null else getString(R.string.count, shown.size))
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        if (Perms.any(this)) {
            message.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            message.text = getString(R.string.no_videos)
        }
    }

    private fun chooseFolder() {
        val groups = all.groupBy { it.bucketId }.map { (id, v) -> Triple(id, v[0].bucket.ifEmpty { "—" }, v.size) }
        val labels = listOf(getString(R.string.all_videos, all.size)) + groups.map { "${it.second}  (${it.third})" }
        AlertDialog.Builder(this)
            .setTitle(R.string.folders)
            .setItems(labels.toTypedArray()) { _, which ->
                folder = if (which == 0) null else groups[which - 1].first
                applyFilter()
                listView.setSelection(0)
            }
            .show()
    }

    private inner class VideoAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(16), dp(8))
                val frame = FrameLayout(context).apply {
                    setBackgroundColor(line())
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                        }
                    }
                    clipToOutline = true
                    addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    addView(label("", 12f, true, android.graphics.Color.WHITE).apply {
                        setBackgroundColor(0xAA000000.toInt())
                        setPadding(dp(5), dp(1), dp(5), dp(1))
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) })
                }
                addView(frame, LinearLayout.LayoutParams(dp(136), dp(77)))
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    addView(label("", 15f).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(label("", 13f, color = sub()).apply { maxLines = 1 })
                    addView(label("", 13f, true).apply { maxLines = 1 })
                }
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val v = shown[position]
            val frame = row.getChildAt(0) as FrameLayout
            Thumbs.load(frame.getChildAt(0) as ImageView, v.uri)
            (frame.getChildAt(1) as TextView).text = timeText(v.duration)
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = v.name
            (texts.getChildAt(1) as TextView).text = "${Formatter.formatShortFileSize(this@MainActivity, v.size)}  ·  ${v.bucket}"
            val resume = Resume.get(this@MainActivity, v.uri.toString())
            val r = texts.getChildAt(2) as TextView
            r.text = if (resume > 0) getString(R.string.resume_from, timeText(resume.toLong())) else ""
            r.visibility = if (resume > 0) View.VISIBLE else View.GONE
            return row
        }
    }

    companion object {
        private const val REQ_PERM = 1
        private const val KEY_FOLDER = "folder"
    }
}
