package com.example.plain.gallery

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 共有の準備。
 * JPEG写真は、位置情報（GPS）や機器のシリアル番号を消したコピーを作ってから渡します。
 * 写真を送った相手に、撮影場所が知られないようにするためです。
 */
object Sharing {
    private val STRIP_TAGS = listOf(
        "GPSVersionID", "GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef", "GPSLongitude",
        "GPSAltitudeRef", "GPSAltitude", "GPSTimeStamp", "GPSSatellites", "GPSStatus", "GPSMeasureMode",
        "GPSDOP", "GPSSpeedRef", "GPSSpeed", "GPSTrackRef", "GPSTrack", "GPSImgDirectionRef",
        "GPSImgDirection", "GPSMapDatum", "GPSDestLatitudeRef", "GPSDestLatitude", "GPSDestLongitudeRef",
        "GPSDestLongitude", "GPSDestBearingRef", "GPSDestBearing", "GPSDestDistanceRef", "GPSDestDistance",
        "GPSProcessingMethod", "GPSAreaInformation", "GPSDateStamp", "GPSDifferential", "GPSHPositioningError",
        "BodySerialNumber", "LensSerialNumber", "CameraOwnerName", "ImageUniqueID",
    )

    fun isStrippable(mime: String) = mime.equals("image/jpeg", ignoreCase = true) || mime.equals("image/jpg", ignoreCase = true)

    /**
     * 共有に使うURIを返す。
     * @param mustCopy 他のアプリから開いた写真など、元のURIをそのまま渡せないとき true
     */
    fun prepare(ctx: Context, src: Uri, mime: String, name: String, mustCopy: Boolean): Uri {
        val strip = isStrippable(mime)
        if (!strip && !mustCopy) return src
        val dir = File(ctx.cacheDir, ShareProvider.DIR).apply { mkdirs() }
        // 1時間より前の共有用コピーは片付ける
        val old = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }

        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifEmpty { if (strip) "photo.jpg" else "file" }
        val out = File(dir, safe)
        val input = ctx.contentResolver.openInputStream(src) ?: throw FileNotFoundException(src.toString())
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        if (strip) {
            val exif = ExifInterface(out.absolutePath)
            for (t in STRIP_TAGS) exif.setAttribute(t, null)
            exif.saveAttributes()
        }
        return ShareProvider.uriFor(ctx, out)
    }
}

/** 共有用コピーだけを、許可したアプリに読み取り専用で渡すための窓口 */
class ShareProvider : ContentProvider() {

    companion object {
        const val DIR = "share"
        fun uriFor(ctx: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority("${ctx.packageName}.share").appendPath(f.name).build()
    }

    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        val dir = File(ctx.cacheDir, DIR).canonicalFile
        val f = File(dir, name).canonicalFile
        return if (f.parentFile == dir && f.isFile) f else null
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        val f = fileFor(uri) ?: return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException()
}
