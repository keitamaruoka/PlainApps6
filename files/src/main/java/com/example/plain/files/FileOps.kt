package com.example.plain.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** コピー・移動などのファイル操作 */
object FileOps {
    /** 同じ名前があれば「名前 (1).txt」のように番号を付ける */
    fun uniqueName(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var i = 1
        while (true) {
            f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }

    fun copy(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.mkdirs() && !dst.isDirectory) throw IOException("cannot create ${dst.path}")
            src.listFiles()?.forEach { copy(it, File(dst, it.name)) }
        } else {
            src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            dst.setLastModified(src.lastModified())
        }
    }

    fun move(src: File, dst: File) {
        if (src.renameTo(dst)) return
        copy(src, dst) // 別のストレージ（SDカードなど）へはコピーしてから元を消す
        if (!src.deleteRecursively()) throw IOException("cannot delete ${src.path}")
    }

    /** ギャラリーなどに変更を知らせるためのパス一覧（多すぎる場合は打ち切り） */
    fun pathsFor(f: File): List<String> =
        if (f.isDirectory) f.walkTopDown().take(5000).map { it.absolutePath }.toList() else listOf(f.absolutePath)

    fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
}

/**
 * ファイルを他のアプリで開くときの窓口。
 * そのとき開いたファイル1つだけを、読み取り専用で相手のアプリに渡します。
 */
class OpenProvider : ContentProvider() {

    companion object {
        fun uriFor(ctx: Context, f: File): Uri {
            val b = Uri.Builder().scheme("content").authority("${ctx.packageName}.open")
            f.absolutePath.split('/').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
            return b.build()
        }
    }

    private fun fileFor(uri: Uri): File? {
        val path = "/" + uri.pathSegments.joinToString("/")
        val f = File(path).canonicalFile
        // 端末の共有ストレージ（内部ストレージ・SDカード）の中だけを許可する
        return if (f.path.startsWith("/storage/") && f.isFile) f else null
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
