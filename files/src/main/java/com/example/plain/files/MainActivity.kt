package com.example.plain.files

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {

    /** 一覧の1行 */
    private class Row(val file: File, val title: String, val subtitle: String, val isDir: Boolean)

    private enum class Sort { NAME, DATE, SIZE }

    private lateinit var bar: TopBar
    private lateinit var listView: ListView
    private lateinit var message: TextView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteLabel: TextView

    /** いま開いているフォルダ（null ならストレージ選択の最初の画面） */
    private var cwd: File? = null
    private var rows: List<Row> = emptyList()
    private var roots: List<File> = emptyList()
    private var lastShown: File? = null

    /** コピー・移動待ちのファイル（second が true なら移動） */
    private var clip: Pair<File, Boolean>? = null

    private var sort = Sort.NAME
    private var showHidden = false
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        sort = Sort.entries.getOrNull(prefs.getInt("sort", 0)) ?: Sort.NAME
        showHidden = prefs.getBoolean("hidden", false)
        savedInstanceState?.getString(KEY_CWD)?.let { cwd = File(it) }
        roots = storageRoots().map { it.first }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
        }
        root.padForSystemBars()

        bar = TopBar(this)
        bar.showBack(getString(R.string.back)) { goBack() }
        bar.action("⋮") { showMenu(it) }.contentDescription = getString(R.string.menu)
        root.addView(bar)

        message = label("", 16f, color = sub()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(64), dp(32), dp(16))
            visibility = View.GONE
        }
        root.addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        listView = ListView(this).apply {
            divider = null
            adapter = RowAdapter()
            setOnItemClickListener { _, _, pos, _ -> onRowClick(rows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> onRowLongClick(rows[pos]); true }
        }
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // コピー・移動中に出る「ここに貼り付け」バー
        pasteBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setBackgroundColor(line())
            visibility = View.GONE
        }
        pasteLabel = label("", 14f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        pasteBar.addView(pasteLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pasteBar.addView(label(getString(R.string.cancel), 15f, true).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            ripple(true)
            setOnClickListener { clip = null; updatePasteBar() }
        })
        pasteBar.addView(label(getString(R.string.paste_here), 15f, true, bg()).apply {
            background = pressable(fg(), 20)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { paste() }
        })
        root.addView(pasteBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
    }

    @Deprecated("Android 12 以前の「戻る」")
    override fun onBackPressed() {
        goBack()
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) load() else showPermissionScreen()
        // 設定で許可してから戻ってきたら、そのままインストールを続ける
        val apk = pendingApk
        if (apk != null && packageManager.canRequestPackageInstalls()) installApk(apk)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cwd?.let { outState.putString(KEY_CWD, it.absolutePath) }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---- 権限 ----

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionScreen() {
        rows = emptyList()
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        bar.titleView.text = getString(R.string.app_name)
        bar.setSubtitle(null)
        message.visibility = View.VISIBLE
        message.text = getString(R.string.need_permission)
        message.setOnClickListener { requestAccess() }
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAccess()) load()
    }

    // ---- 一覧の読み込み ----

    private fun storageRoots(): List<Pair<File, String>> {
        val out = ArrayList<Pair<File, String>>()
        val primary = Environment.getExternalStorageDirectory()
        out += primary to getString(R.string.internal_storage)
        if (Build.VERSION.SDK_INT >= 30) {
            val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                val dir = v.directory ?: continue
                if (v.isPrimary || dir == primary) continue
                out += dir to v.getDescription(this)
            }
        }
        return out
    }

    private fun load() {
        message.setOnClickListener(null)
        val dir = cwd
        if (dir == null) {
            loadRoot()
            return
        }
        bar.titleView.text = if (roots.contains(dir)) displayRootName(dir) else dir.name
        bar.setSubtitle(displayPath(dir))
        val hidden = showHidden
        val order = sort
        io.execute {
            val files = dir.listFiles()
            val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
            val list = files
                ?.filter { hidden || !it.name.startsWith(".") }
                ?.map { f -> Row(f, f.name, describe(f, df), f.isDirectory) }
                ?.sortedWith(comparator(order))
            runOnUiThread {
                if (isDestroyed || cwd != dir) return@runOnUiThread
                rows = list ?: emptyList()
                (listView.adapter as BaseAdapter).notifyDataSetChanged()
                if (lastShown != dir) listView.setSelection(0) // 別のフォルダに移ったときだけ先頭へ
                lastShown = dir
                message.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                message.text = getString(if (list == null) R.string.no_access_folder else R.string.empty_folder)
                updatePasteBar()
            }
        }
    }

    private fun loadRoot() {
        val vols = storageRoots()
        roots = vols.map { it.first }
        bar.titleView.text = getString(R.string.app_name)
        bar.setSubtitle(null)
        val out = ArrayList<Row>()
        for ((dir, name) in vols) {
            val free = try { Formatter.formatShortFileSize(this, dir.freeSpace) } catch (e: Exception) { "" }
            out += Row(dir, name, getString(R.string.free_space, free), true)
        }
        val primary = vols.first().first
        val shortcuts = listOf(
            Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES,
        )
        for (s in shortcuts) {
            val f = File(primary, s)
            if (f.isDirectory) out += Row(f, f.name, displayPath(f), true)
        }
        rows = out
        lastShown = null
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        message.visibility = View.GONE
        updatePasteBar()
    }

    private fun describe(f: File, df: DateFormat): String =
        if (f.isDirectory) {
            val n = f.list()?.count { showHidden || !it.startsWith(".") }
            if (n == null) "" else resources.getQuantityString(R.plurals.items, n, n)
        } else {
            "${Formatter.formatShortFileSize(this, f.length())}  ·  ${df.format(Date(f.lastModified()))}"
        }

    private fun comparator(order: Sort): Comparator<Row> {
        val dirsFirst = compareByDescending<Row> { it.isDir }
        return when (order) {
            Sort.NAME -> dirsFirst.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            Sort.DATE -> dirsFirst.thenByDescending { it.file.lastModified() }
            Sort.SIZE -> dirsFirst.thenByDescending { if (it.isDir) 0L else it.file.length() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        }
    }

    private fun displayRootName(dir: File): String =
        storageRoots().firstOrNull { it.first == dir }?.second ?: dir.name

    /** 「/storage/emulated/0/Download」を「内部ストレージ / Download」のように見やすくする */
    private fun displayPath(f: File): String {
        for ((dir, name) in storageRoots()) {
            val base = dir.absolutePath
            if (f.absolutePath == base) return name
            if (f.absolutePath.startsWith("$base/")) return name + f.absolutePath.removePrefix(base).replace("/", " / ")
        }
        return f.absolutePath
    }

    private fun open(dir: File?) {
        cwd = dir
        load()
    }

    private fun goBack() {
        val dir = cwd
        when {
            dir == null -> finish()
            roots.contains(dir) -> open(null)
            else -> {
                val parent = dir.parentFile
                // 内部ストレージより上（/storage など）には行かない
                open(if (parent == null || roots.none { parent.absolutePath.startsWith(it.absolutePath) }) null else parent)
            }
        }
    }

    // ---- 行の操作 ----

    private fun onRowClick(r: Row) {
        when {
            r.isDir -> open(r.file)
            r.file.extension.equals("apk", ignoreCase = true) -> installApk(r.file)
            else -> openFile(r.file, chooser = false)
        }
    }

    /** 「この提供元のアプリを許可」の設定から戻ってきたら、続けてインストールするためのAPK */
    private var pendingApk: File? = null

    /**
     * APKのインストール。
     * Androidでは、ファイルアプリが「不明なアプリのインストール」を許可されていないとインストーラーが開かないため、
     * 許可がなければ先に設定画面を開く。
     */
    private fun installApk(f: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingApk = f
            toast(getString(R.string.allow_install))
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.failed))
            }
            return
        }
        pendingApk = null
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(OpenProvider.uriFor(this, f), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app))
        }
    }

    private fun openFile(f: File, chooser: Boolean) {
        val uri = OpenProvider.uriFor(this, f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, FileOps.mimeOf(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(if (chooser) Intent.createChooser(i, getString(R.string.open_with)) else i)
        } catch (e: ActivityNotFoundException) {
            if (!chooser) openFile(f, chooser = true) else toast(getString(R.string.no_app))
        }
    }

    private fun shareFile(f: File) {
        val uri = OpenProvider.uriFor(this, f)
        val send = Intent(Intent.ACTION_SEND)
            .setType(FileOps.mimeOf(f).let { if (it == "*/*") "application/octet-stream" else it })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri)
        startActivity(Intent.createChooser(send, null))
    }

    private fun onRowLongClick(r: Row) {
        if (cwd == null) return // 最初の画面のストレージは操作しない
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (!r.isDir) {
            if (r.file.extension.equals("apk", ignoreCase = true)) actions += getString(R.string.install) to { installApk(r.file) }
            actions += getString(R.string.open_with) to { openFile(r.file, chooser = true) }
            actions += getString(R.string.share) to { shareFile(r.file) }
        }
        actions += getString(R.string.rename) to { rename(r.file) }
        actions += getString(R.string.copy) to { clip = r.file to false; updatePasteBar() }
        actions += getString(R.string.move) to { clip = r.file to true; updatePasteBar() }
        actions += getString(R.string.delete) to { confirmDelete(r.file) }
        actions += getString(R.string.details) to { details(r.file) }
        AlertDialog.Builder(this)
            .setTitle(r.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun nameDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(initial)
            setSingleLine()
            val dot = initial.lastIndexOf('.')
            if (dot > 0) setSelection(0, dot) else selectAll()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = edit.text.toString().trim()
                when {
                    name.isEmpty() || name == "." || name == ".." || name.contains('/') -> toast(getString(R.string.bad_name))
                    else -> onOk(name)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        edit.requestFocus()
    }

    private fun rename(f: File) {
        nameDialog(getString(R.string.rename), f.name) { name ->
            val target = File(f.parentFile, name)
            if (target.exists()) {
                toast(getString(R.string.already_exists))
                return@nameDialog
            }
            val before = FileOps.pathsFor(f)
            if (f.renameTo(target)) {
                scan(before + FileOps.pathsFor(target))
                load()
            } else {
                toast(getString(R.string.failed))
            }
        }
    }

    private fun newFolder() {
        val dir = cwd ?: return
        nameDialog(getString(R.string.new_folder), getString(R.string.new_folder_default)) { name ->
            val f = File(dir, name)
            if (f.exists()) toast(getString(R.string.already_exists))
            else if (!f.mkdir()) toast(getString(R.string.failed))
            else load()
        }
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, f.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                runWithProgress(
                    work = {
                        val paths = FileOps.pathsFor(f)
                        val ok = f.deleteRecursively()
                        scan(paths)
                        ok
                    },
                    done = { ok -> if (!ok) toast(getString(R.string.failed)) },
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun details(f: File) {
        val df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
        val size = if (f.isDirectory) {
            f.walkTopDown().take(20000).filter { it.isFile }.sumOf { it.length() }
        } else f.length()
        val text = listOf(
            getString(R.string.info_name) to f.name,
            getString(R.string.info_location) to displayPath(f.parentFile ?: f),
            getString(R.string.info_size) to Formatter.formatFileSize(this, size),
            getString(R.string.info_modified) to df.format(Date(f.lastModified())),
        ).joinToString("\n\n") { "${it.first}\n${it.second}" }
        AlertDialog.Builder(this).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    // ---- コピー・移動 ----

    private fun updatePasteBar() {
        val c = clip
        if (c == null || cwd == null) {
            pasteBar.visibility = View.GONE
            return
        }
        pasteBar.visibility = View.VISIBLE
        pasteLabel.text = getString(if (c.second) R.string.moving else R.string.copying, c.first.name)
    }

    private fun paste() {
        val (src, cut) = clip ?: return
        val dst = cwd ?: return
        val srcPath = src.canonicalPath
        if (src.isDirectory && (dst.canonicalPath + "/").startsWith("$srcPath/")) {
            toast(getString(R.string.into_itself))
            return
        }
        if (cut && src.parentFile?.canonicalPath == dst.canonicalPath) {
            clip = null
            updatePasteBar()
            return
        }
        val target = FileOps.uniqueName(dst, src.name)
        clip = null
        runWithProgress(
            work = {
                val before = FileOps.pathsFor(src)
                val ok = try {
                    if (cut) FileOps.move(src, target) else FileOps.copy(src, target)
                    true
                } catch (e: Exception) {
                    false
                }
                scan((if (cut) before else emptyList()) + FileOps.pathsFor(target))
                ok
            },
            done = { ok -> if (!ok) toast(getString(R.string.failed)) },
        )
    }

    /** 時間のかかる作業を裏で行い、その間は「処理中」を表示する */
    private fun runWithProgress(work: () -> Boolean, done: (Boolean) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(ProgressBar(context))
            addView(label(getString(R.string.working), 16f).apply { setPadding(dp(20), 0, 0, 0) })
        }
        val dlg = AlertDialog.Builder(this).setView(box).setCancelable(false).show()
        io.execute {
            val ok = work()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dlg.dismiss()
                done(ok)
                load()
            }
        }
    }

    /** 変更をギャラリーなど他のアプリに知らせる */
    private fun scan(paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(applicationContext, paths.toTypedArray(), null, null)
    }

    // ---- メニュー ----

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        if (cwd != null) m.menu.add(0, 1, 0, R.string.new_folder)
        m.menu.add(0, 2, 1, getString(R.string.sort_by, getString(sortLabel(sort))))
        m.menu.add(0, 3, 2, if (showHidden) R.string.hide_hidden else R.string.show_hidden)
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newFolder()
                2 -> chooseSort()
                3 -> {
                    showHidden = !showHidden
                    saveSettings()
                    load()
                }
            }
            true
        }
        m.show()
    }

    private fun sortLabel(s: Sort) = when (s) {
        Sort.NAME -> R.string.sort_name
        Sort.DATE -> R.string.sort_date
        Sort.SIZE -> R.string.sort_size
    }

    private fun chooseSort() {
        val labels = Sort.entries.map { getString(sortLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.sort)
            .setSingleChoiceItems(labels, sort.ordinal) { d, which ->
                sort = Sort.entries[which]
                saveSettings()
                d.dismiss()
                load()
            }
            .show()
    }

    private fun saveSettings() {
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putInt("sort", sort.ordinal)
            .putBoolean("hidden", showHidden)
            .apply()
    }

    // ---- 一覧の見た目 ----

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(28), dp(28)))
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), 0, 0, 0)
                    addView(label("", 16f).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                    addView(label("", 13f, color = sub()).apply { maxLines = 1 })
                }
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val r = rows[position]
            (v.getChildAt(0) as ImageView).setImageResource(
                when {
                    cwd == null && roots.contains(r.file) -> R.drawable.ic_storage
                    r.isDir -> R.drawable.ic_folder
                    else -> R.drawable.ic_file
                },
            )
            val texts = v.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = r.title
            val sub = texts.getChildAt(1) as TextView
            sub.text = r.subtitle
            sub.visibility = if (r.subtitle.isEmpty()) View.GONE else View.VISIBLE
            v.alpha = if (r.title.startsWith(".")) 0.55f else 1f
            return v
        }
    }

    companion object {
        private const val KEY_CWD = "cwd"
    }
}
