package com.example.plain.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** 正方形の画像枠 */
class SquareImageView(ctx: Context) : ImageView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** 角を丸く切り抜く */
fun View.roundCorners(radiusPx: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }
    clipToOutline = true
}

/** アルバム一覧の画面 */
class MainActivity : Activity() {

    private lateinit var grid: GridView
    private lateinit var message: TextView
    private lateinit var banner: TextView
    private var albums: List<Album> = emptyList()
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
        }
        root.padForSystemBars(bottom = false)

        val bar = TopBar(this)
        bar.titleView.text = getString(R.string.app_name)
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

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(150)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(10)
            verticalSpacing = dp(14)
            setPadding(dp(12), dp(4), dp(12), dp(24))
            clipToPadding = false
            selector = getDrawable(android.R.color.transparent)
            adapter = AlbumAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                val a = albums[pos]
                startActivity(
                    Intent(this@MainActivity, AlbumActivity::class.java)
                        .putExtra(AlbumActivity.EXTRA_BUCKET, a.id)
                        .putExtra(AlbumActivity.EXTRA_NAME, a.name),
                )
            }
        }
        grid.padForSystemBars(top = false)
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (!Perms.any(this)) requestPermissions(Perms.needed(), REQ_PERM)
    }

    override fun onResume() {
        super.onResume()
        if (Perms.any(this)) reload() else showDenied()
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
        albums = emptyList()
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        banner.visibility = View.GONE
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
        io.execute {
            val result = MediaRepo.albums(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                albums = result
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
                message.setOnClickListener(null)
                message.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
                message.text = getString(R.string.no_media)
            }
        }
    }

    private inner class AlbumAdapter : BaseAdapter() {
        override fun getCount() = albums.size
        override fun getItem(position: Int) = albums[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val img = SquareImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(line())
                    roundCorners(dp(14).toFloat())
                }
                addView(img, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(label("", 15f, true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(6), dp(2), 0)
                })
                addView(label("", 13f, color = sub()).apply { setPadding(dp(2), 0, dp(2), 0) })
            }
            val a = albums[position]
            Thumbs.load(cell.getChildAt(0) as ImageView, a.cover.uri)
            (cell.getChildAt(1) as TextView).text = a.name
            (cell.getChildAt(2) as TextView).text = a.count.toString()
            return cell
        }
    }

    companion object {
        private const val REQ_PERM = 1
    }
}
