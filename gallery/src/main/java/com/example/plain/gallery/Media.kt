package com.example.plain.gallery

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/** 写真・動画1件分 */
data class Media(
    val id: Long,
    val isVideo: Boolean,
    val bucketId: String,
    val bucket: String,
    val name: String,
    val mime: String,
    val time: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id,
        )
}

data class Album(val id: String, val name: String, val cover: Media, val count: Int)

/** 端末の写真・動画の一覧を取得する（Android標準の MediaStore を読むだけで、どこにも送信しません） */
object MediaRepo {
    const val ALL = "__all__"
    const val TRASH = "__trash__"

    private val PROJECTION = arrayOf(
        "_id", "media_type", "bucket_id", "bucket_display_name", "_display_name",
        "mime_type", "datetaken", "date_modified", "_size", "width", "height", "duration",
    )
    private const val SELECTION = "media_type=? OR media_type=?"
    private val ARGS = arrayOf("1", "3") // 1=画像, 3=動画

    private fun query(ctx: Context, trashed: Boolean): List<Media> {
        val uri = MediaStore.Files.getContentUri("external")
        val cursor: Cursor? = if (Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, SELECTION)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, ARGS)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
            }
            ctx.contentResolver.query(uri, PROJECTION, args, null)
        } else {
            if (trashed) return emptyList()
            ctx.contentResolver.query(uri, PROJECTION, SELECTION, ARGS, null)
        }
        val out = ArrayList<Media>()
        cursor?.use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iType = c.getColumnIndexOrThrow("media_type")
            val iBid = c.getColumnIndexOrThrow("bucket_id")
            val iBname = c.getColumnIndexOrThrow("bucket_display_name")
            val iName = c.getColumnIndexOrThrow("_display_name")
            val iMime = c.getColumnIndexOrThrow("mime_type")
            val iTaken = c.getColumnIndexOrThrow("datetaken")
            val iMod = c.getColumnIndexOrThrow("date_modified")
            val iSize = c.getColumnIndexOrThrow("_size")
            val iW = c.getColumnIndexOrThrow("width")
            val iH = c.getColumnIndexOrThrow("height")
            val iDur = c.getColumnIndexOrThrow("duration")
            while (c.moveToNext()) {
                val isVideo = c.getInt(iType) == 3
                val taken = if (c.isNull(iTaken)) 0L else c.getLong(iTaken)
                out += Media(
                    id = c.getLong(iId),
                    isVideo = isVideo,
                    bucketId = c.getString(iBid) ?: "",
                    bucket = c.getString(iBname) ?: "",
                    name = c.getString(iName) ?: "",
                    mime = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*",
                    time = if (taken > 0) taken else c.getLong(iMod) * 1000,
                    size = if (c.isNull(iSize)) 0L else c.getLong(iSize),
                    width = if (c.isNull(iW)) 0 else c.getInt(iW),
                    height = if (c.isNull(iH)) 0 else c.getInt(iH),
                    duration = if (c.isNull(iDur)) 0L else c.getLong(iDur),
                )
            }
        }
        out.sortByDescending { it.time }
        return out
    }

    /** アルバム（またはすべて・ゴミ箱）の中身を新しい順に */
    fun load(ctx: Context, bucket: String): List<Media> {
        val trashed = bucket == TRASH
        val all = query(ctx, trashed)
        return if (bucket == ALL || trashed) all else all.filter { it.bucketId == bucket }
    }

    /** アルバム一覧。先頭は「すべて」、最後に「ゴミ箱」（中身があるときだけ） */
    fun albums(ctx: Context): List<Album> {
        val list = query(ctx, false)
        val res = ArrayList<Album>()
        if (list.isNotEmpty()) {
            res += Album(ALL, ctx.getString(R.string.all), list[0], list.size)
            list.groupBy { it.bucketId }.forEach { (id, items) ->
                res += Album(id, items[0].bucket.ifEmpty { "—" }, items[0], items.size)
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val trash = query(ctx, true)
            if (trash.isNotEmpty()) res += Album(TRASH, ctx.getString(R.string.trash), trash[0], trash.size)
        }
        return res
    }
}

/** 写真・動画を見るための権限 */
object Perms {
    fun needed(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** すべての写真を見られる */
    fun full(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) has(ctx, Manifest.permission.READ_MEDIA_IMAGES) || has(ctx, Manifest.permission.READ_MEDIA_VIDEO)
        else has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    /** 「選んだ写真のみ許可」の状態（Android 14以降） */
    fun partial(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 && !full(ctx) && has(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun any(ctx: Context) = full(ctx) || partial(ctx)
}

/** サムネイルを裏で読み込み、メモリに少し覚えておく */
object Thumbs {
    const val SIZE = 320
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun peek(uri: Uri): Bitmap? = cache.get(uri.toString())

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
            if (view.tag != key) return@execute // もう画面外に流れた
            val bmp = try {
                resolver.loadThumbnail(uri, Size(SIZE, SIZE), null)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            main.post { if (view.tag == key && bmp != null) view.setImageBitmap(bmp) }
        }
    }
}
