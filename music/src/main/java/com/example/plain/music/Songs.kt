package com.example.plain.music

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
import java.util.concurrent.Executors

/** 1曲分 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val track: Int,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

/** 端末の音楽の一覧を読む（どこにも送信しません） */
object MusicRepo {
    fun query(ctx: Context): List<Song> {
        val proj = arrayOf("_id", "title", "artist", "album", "album_id", "duration", "track", "_display_name")
        val out = ArrayList<Song>()
        ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "is_music != 0", null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iTitle = c.getColumnIndexOrThrow("title")
            val iArtist = c.getColumnIndexOrThrow("artist")
            val iAlbum = c.getColumnIndexOrThrow("album")
            val iAlbumId = c.getColumnIndexOrThrow("album_id")
            val iDur = c.getColumnIndexOrThrow("duration")
            val iTrack = c.getColumnIndexOrThrow("track")
            val iName = c.getColumnIndexOrThrow("_display_name")
            while (c.moveToNext()) {
                val artist = c.getString(iArtist)
                out += Song(
                    id = c.getLong(iId),
                    title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: c.getString(iName) ?: "",
                    artist = if (artist.isNullOrBlank() || artist == "<unknown>") "" else artist,
                    album = c.getString(iAlbum) ?: "",
                    albumId = c.getLong(iAlbumId),
                    duration = if (c.isNull(iDur)) 0L else c.getLong(iDur),
                    track = if (c.isNull(iTrack)) 0 else c.getInt(iTrack),
                )
            }
        }
        out.sortWith(compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title })
        return out
    }
}

object Perms {
    fun needed(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun granted(ctx: Context) = ctx.checkSelfPermission(needed()[0]) == PackageManager.PERMISSION_GRANTED
}

/** ジャケット画像（曲ファイルに埋め込まれたもの）を裏で読み込む */
object Art {
    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<Long>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun peek(song: Song): Bitmap? = cache.get(song.albumId)

    /** 読み込めたら onLoaded を呼ぶ（画像が無ければ null） */
    fun load(ctx: Context, song: Song, onLoaded: (Bitmap?) -> Unit) {
        cache.get(song.albumId)?.let {
            onLoaded(it)
            return
        }
        if (synchronized(missing) { song.albumId in missing }) {
            onLoaded(null)
            return
        }
        val resolver = ctx.applicationContext.contentResolver
        pool.execute {
            val bmp = try {
                resolver.loadThumbnail(song.uri, Size(512, 512), null)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(song.albumId, bmp) else synchronized(missing) { missing += song.albumId }
            main.post { onLoaded(bmp) }
        }
    }
}

fun timeText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
