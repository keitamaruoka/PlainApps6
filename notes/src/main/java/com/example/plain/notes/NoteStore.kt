package com.example.plain.notes

import android.content.Context
import java.io.File

/** メモ1件。1行目をタイトルとして扱う */
data class Note(val id: String, val text: String, val modified: Long) {
    val title: String get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
    val preview: String
        get() {
            val lines = text.lines()
            val first = lines.indexOfFirst { it.isNotBlank() }
            return if (first < 0) "" else lines.drop(first + 1).joinToString(" ") { it.trim() }.trim()
        }
}

/**
 * メモの保存場所。アプリ専用の領域に1件1ファイルのテキストとして保存します。
 * 他のアプリからは読めず、クラウドへのバックアップもしません。
 */
object NoteStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "notes").apply { mkdirs() }

    fun list(ctx: Context): List<Note> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.map { Note(it.nameWithoutExtension, it.readText(), it.lastModified()) }
            ?.sortedByDescending { it.modified }
            ?: emptyList()

    fun load(ctx: Context, id: String): Note? {
        val f = File(dir(ctx), "$id.txt")
        return if (f.isFile) Note(id, f.readText(), f.lastModified()) else null
    }

    /** 保存（空になったメモは削除）。書きかけで壊れないよう、一時ファイルに書いてから置き換える */
    fun save(ctx: Context, id: String, text: String) {
        val f = File(dir(ctx), "$id.txt")
        if (text.isBlank()) {
            f.delete()
            return
        }
        if (f.isFile && f.readText() == text) return // 変更なし（更新日時を変えない）
        val tmp = File(dir(ctx), "$id.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.txt").delete()
    }

    fun newId(): String = System.currentTimeMillis().toString(36) + (1000..9999).random().toString(36)
}
