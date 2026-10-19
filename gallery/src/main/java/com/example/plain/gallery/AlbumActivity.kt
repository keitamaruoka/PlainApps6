package com.example.plain.gallery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** 1つのアルバムの写真を並べる画面 */
class AlbumActivity : Activity() {

    companion object {
        const val EXTRA_BUCKET = "bucket"
        const val EXTRA_NAME = "name"
        private const val REQ_EMPTY_TRASH = 10

        fun durationText(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }

    private lateinit var bucket: String
    private lateinit var bar: TopBar
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private var items: List<Media> = emptyList()
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        bucket = intent.getStringExtra(EXTRA_BUCKET) ?: MediaRepo.ALL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
        }
        root.padForSystemBars(bottom = false)

        bar = TopBar(this)
        bar.showBack(getString(R.string.back)) { finish() }
        bar.titleView.text = intent.getStringExtra(EXTRA_NAME) ?: ""
        if (bucket == MediaRepo.TRASH && Build.VERSION.SDK_INT >= 30) {
            bar.action(getString(R.string.empty_trash)) { confirmEmptyTrash() }
        }
        root.addView(bar)

        empty = label(getString(R.string.no_media), 16f, color = sub()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(80), dp(32), 0)
            visibility = View.GONE
        }
        root.addView(empty)

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(96)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            clipToPadding = false
            selector = getDrawable(android.R.color.transparent)
            adapter = GridAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                val m = items[pos]
                startActivity(
                    Intent(this@AlbumActivity, ViewerActivity::class.java)
                        .putExtra(ViewerActivity.EXTRA_BUCKET, bucket)
                        .putExtra(ViewerActivity.EXTRA_ID, m.id),
                )
            }
        }
        grid.padForSystemBars(top = false)
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun reload() {
        io.execute {
            val list = MediaRepo.load(this, bucket)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                items = list
                bar.setSubtitle(getString(R.string.count, list.size))
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
    }

    private fun confirmEmptyTrash() {
        if (items.isEmpty() || Build.VERSION.SDK_INT < 30) return
        try {
            val req = MediaStore.createDeleteRequest(contentResolver, items.map { it.uri })
            startIntentSenderForResult(req.intentSender, REQ_EMPTY_TRASH, null, 0, 0, 0)
        } catch (e: Exception) {
            AlertDialog.Builder(this).setMessage(R.string.failed).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EMPTY_TRASH) reload()
    }

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = convertView as? FrameLayout ?: FrameLayout(this@AlbumActivity).apply {
                addView(SquareImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(line())
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                addView(label("", 12f, true, Color.WHITE).apply {
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END,
                ))
            }
            val m = items[position]
            Thumbs.load(cell.getChildAt(0) as ImageView, m.uri)
            val badge = cell.getChildAt(1) as TextView
            badge.text = if (m.isVideo) "▶ " + durationText(m.duration) else ""
            badge.visibility = if (m.isVideo) View.VISIBLE else View.GONE
            return cell
        }
    }
}
