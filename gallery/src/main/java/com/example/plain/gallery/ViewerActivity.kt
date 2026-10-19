package com.example.plain.gallery

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** 写真を1枚ずつ大きく見る画面 */
class ViewerActivity : Activity() {

    companion object {
        const val EXTRA_BUCKET = "bucket"
        const val EXTRA_ID = "id"
        private const val REQ_WRITE = 20
        private const val REQ_DELETE_Q = 21
    }

    private var items: List<Media> = emptyList()
    private var index = 0
    private var bucket = MediaRepo.ALL

    /** 他のアプリから「開く」で渡された写真 */
    private var external: Uri? = null
    private var externalMime: String = "image/*"

    private lateinit var image: ZoomImageView
    private lateinit var play: TextView
    private lateinit var top: TopBar
    private lateinit var bottom: LinearLayout
    private var barsVisible = true
    private var decodeGen = 0
    private var pendingDelete: Media? = null
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ZoomImageView(this).apply {
            onTap = { toggleBars() }
            onSwipe = { dir -> move(dir) }
        }
        root.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        play = label("▶", 34f, false, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = pressable(0x88000000.toInt(), 44)
            contentDescription = getString(R.string.play)
            visibility = View.GONE
            setOnClickListener { playVideo() }
        }
        root.addView(play, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))

        top = TopBar(this, Color.WHITE).apply { setBackgroundColor(0x99000000.toInt()) }
        top.showBack(getString(R.string.back)) { finish() }
        top.padForSystemBars(bottom = false)
        root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setPadding(0, dp(4), 0, dp(4))
        }
        bottom.padForSystemBars(top = false)
        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null) {
            external = data
            externalMime = intent.type ?: contentResolver.getType(data) ?: "image/*"
            setupActions()
            showExternal()
        } else {
            bucket = intent.getStringExtra(EXTRA_BUCKET) ?: MediaRepo.ALL
            val id = intent.getLongExtra(EXTRA_ID, -1)
            setupActions()
            io.execute {
                val list = MediaRepo.load(this, bucket)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    items = list
                    index = list.indexOfFirst { it.id == id }.coerceAtLeast(0)
                    show()
                }
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---- 下のボタン ----

    private fun setupActions() {
        bottom.removeAllViews()
        fun add(text: String, onClick: () -> Unit) {
            val b = label(text, 15f, true, Color.WHITE).apply {
                gravity = Gravity.CENTER
                minHeight = dp(52)
                ripple(true)
                setOnClickListener { onClick() }
            }
            bottom.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        when {
            external != null -> add(getString(R.string.share)) { share() }
            bucket == MediaRepo.TRASH -> {
                add(getString(R.string.restore)) { current()?.let { trash(it, false) } }
                add(getString(R.string.delete_forever)) { current()?.let { deleteForever(it) } }
            }
            else -> {
                add(getString(R.string.share)) { share() }
                add(getString(R.string.info)) { info() }
                add(getString(R.string.delete)) { current()?.let { delete(it) } }
            }
        }
    }

    private fun current(): Media? = items.getOrNull(index)

    // ---- 表示 ----

    private fun show() {
        if (items.isEmpty()) {
            finish()
            return
        }
        index = index.coerceIn(0, items.size - 1)
        val m = items[index]
        top.titleView.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.time))
        top.setSubtitle("${index + 1} / ${items.size}")
        play.visibility = if (m.isVideo) View.VISIBLE else View.GONE
        image.setImage(Thumbs.peek(m.uri)) // まずは小さい画像をすぐ出して、そのあと高画質に差し替える
        decode(m.uri, m.isVideo)
    }

    private fun showExternal() {
        val uri = external ?: return
        val video = externalMime.startsWith("video")
        top.titleView.text = uri.lastPathSegment ?: ""
        play.visibility = if (video) View.VISIBLE else View.GONE
        decode(uri, video)
    }

    private fun move(dir: Int): Boolean {
        if (external != null) return false
        val n = index + dir
        if (n !in items.indices) return false
        index = n
        show()
        return true
    }

    private fun decode(uri: Uri, video: Boolean) {
        val gen = ++decodeGen
        val dm = resources.displayMetrics
        val max = (maxOf(dm.widthPixels, dm.heightPixels) * 1.5f).toInt().coerceAtMost(4096)
        io.execute {
            if (gen != decodeGen) return@execute
            val bmp: Bitmap? = try {
                if (video) videoFrame(uri, max) else ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = max.toFloat() / maxOf(w, h)
                    if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            runOnUiThread {
                if (gen != decodeGen || isDestroyed) return@runOnUiThread
                if (bmp != null) image.setImage(bmp) else if (image.drawable == null) toast(getString(R.string.cannot_open))
            }
        }
    }

    private fun videoFrame(uri: Uri, max: Int): Bitmap? {
        try {
            return contentResolver.loadThumbnail(uri, Size(max, max), null)
        } catch (e: Exception) {
            // 他のアプリから渡された動画などはこちらで1コマ取り出す
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            r.frameAtTime
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    private fun playVideo() {
        val uri = external ?: current()?.uri ?: return
        startActivity(Intent(this, VideoActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    @Suppress("DEPRECATION")
    private fun toggleBars() {
        barsVisible = !barsVisible
        val v = if (barsVisible) View.VISIBLE else View.GONE
        top.visibility = v
        bottom.visibility = v
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (barsVisible) {
                c.show(WindowInsets.Type.systemBars())
            } else {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (barsVisible) 0 else View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    // ---- 共有・情報・削除 ----

    private fun share() {
        val ext = external
        val m = current()
        val src = ext ?: m?.uri ?: return
        val mime = if (ext != null) externalMime else m!!.mime
        val name = if (ext != null) (ext.lastPathSegment ?: "file") else m!!.name
        io.execute {
            val shareUri = try {
                Sharing.prepare(this, src, mime, name, mustCopy = ext != null)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (shareUri == null) {
                    toast(getString(R.string.failed))
                    return@runOnUiThread
                }
                val send = Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, shareUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri("", shareUri)
                if (Sharing.isStrippable(mime)) toast(getString(R.string.location_removed))
                startActivity(Intent.createChooser(send, null))
            }
        }
    }

    private fun info() {
        val m = current() ?: return
        val lines = mutableListOf(
            getString(R.string.info_name) to m.name,
            getString(R.string.info_date) to DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(m.time)),
            getString(R.string.info_size) to Formatter.formatShortFileSize(this, m.size),
            getString(R.string.info_album) to m.bucket,
        )
        if (m.width > 0 && m.height > 0) lines += getString(R.string.info_resolution) to "${m.width} × ${m.height}"
        if (m.isVideo) lines += getString(R.string.info_duration) to AlbumActivity.durationText(m.duration)
        lines += getString(R.string.info_type) to m.mime
        val text = lines.joinToString("\n\n") { "${it.first}\n${it.second}" } +
            if (Sharing.isStrippable(m.mime)) "\n\n" + getString(R.string.info_privacy) else ""
        AlertDialog.Builder(this)
            .setTitle(R.string.info)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun delete(m: Media) {
        if (Build.VERSION.SDK_INT >= 30) {
            trash(m, true) // ゴミ箱へ（30日以内なら元に戻せる）
        } else {
            AlertDialog.Builder(this)
                .setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete) { _, _ -> deleteForever(m) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun trash(m: Media, toTrash: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val req = MediaStore.createTrashRequest(contentResolver, listOf(m.uri), toTrash)
            pendingDelete = m
            startIntentSenderForResult(req.intentSender, REQ_WRITE, null, 0, 0, 0)
        } catch (e: Exception) {
            toast(getString(R.string.failed))
        }
    }

    private fun deleteForever(m: Media) {
        pendingDelete = m
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val req = MediaStore.createDeleteRequest(contentResolver, listOf(m.uri))
                startIntentSenderForResult(req.intentSender, REQ_WRITE, null, 0, 0, 0)
            } catch (e: Exception) {
                toast(getString(R.string.failed))
            }
        } else {
            try {
                contentResolver.delete(m.uri, null, null)
                removed(m)
            } catch (e: RecoverableSecurityException) {
                // Android 10: 他のアプリが保存した写真は、確認画面で許可をもらってから消す
                try {
                    startIntentSenderForResult(e.userAction.actionIntent.intentSender, REQ_DELETE_Q, null, 0, 0, 0)
                } catch (e2: Exception) {
                    toast(getString(R.string.failed))
                }
            } catch (e: Exception) {
                toast(getString(R.string.failed))
            }
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val m = pendingDelete ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_WRITE -> removed(m)
            REQ_DELETE_Q -> deleteForever(m)
        }
    }

    /** 削除・移動した写真を一覧から外して、次の写真を出す */
    private fun removed(m: Media) {
        pendingDelete = null
        items = items.filter { it.id != m.id }
        show()
    }
}
