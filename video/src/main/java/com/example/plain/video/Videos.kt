package com.example.plain.video

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/** 動画1本分 */
data class Video(
    val id: Long,
    val name: String,
    val bucketId: String,
    val bucket: String,
    val duration: Long,
    val size: Long,
    val time: Long,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
}

/** 端末の動画の一覧を読む（どこにも送信しません） */
object VideoRepo {
    fun query(ctx: Context): List<Video> {
        val proj = arrayOf("_id", "_display_name", "bucket_id", "bucket_display_name", "duration", "_size", "date_modified")
        val out = ArrayList<Video>()
        ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iName = c.getColumnIndexOrThrow("_display_name")
            val iBid = c.getColumnIndexOrThrow("bucket_id")
            val iBname = c.getColumnIndexOrThrow("bucket_display_name")
            val iDur = c.getColumnIndexOrThrow("duration")
            val iSize = c.getColumnIndexOrThrow("_size")
            val iMod = c.getColumnIndexOrThrow("date_modified")
            while (c.moveToNext()) {
                out += Video(
                    id = c.getLong(iId),
                    name = c.getString(iName) ?: "",
                    bucketId = c.getString(iBid) ?: "",
                    bucket = c.getString(iBname) ?: "",
                    duration = if (c.isNull(iDur)) 0L else c.getLong(iDur),
                    size = if (c.isNull(iSize)) 0L else c.getLong(iSize),
                    time = c.getLong(iMod) * 1000,
                )
            }
        }
        out.sortByDescending { it.time }
        return out
    }
}

object Perms {
    fun needed(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun full(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) has(ctx, Manifest.permission.READ_MEDIA_VIDEO)
        else has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    fun partial(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 && !full(ctx) && has(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun any(ctx: Context) = full(ctx) || partial(ctx)
}

/** サムネイルを裏で読み込み、少しだけメモリに覚えておく */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, uri: Uri) {
        val key = uri.toString()
        view.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        val resolver = view.context.applicationContext.contentResolver
        pool.execute {
            if (view.tag != key) return@execute
            val bmp = try {
                resolver.loadThumbnail(uri, Size(480, 270), null)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            main.post { if (view.tag == key && bmp != null) view.setImageBitmap(bmp) }
        }
    }
}

/** 「続きから再生」のための再生位置（この端末の中だけに保存） */
object Resume {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): Int = prefs(ctx).getInt(key, 0)

    fun put(ctx: Context, key: String, positionMs: Int, durationMs: Int) {
        val p = prefs(ctx).edit()
        // 冒頭5秒以内や最後の5秒は、覚えずに最初から
        if (positionMs < 5000 || (durationMs > 0 && positionMs > durationMs - 5000)) p.remove(key) else p.putInt(key, positionMs)
        p.apply()
    }
}

fun timeText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
