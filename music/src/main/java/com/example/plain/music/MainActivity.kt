package com.example.plain.music

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import java.util.concurrent.Executors

/** 角を丸く切り抜く */
fun View.roundCorners(radiusPx: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }
    clipToOutline = true
}

/** 曲・アルバム・アーティストの一覧と、下のミニプレーヤー */
class MainActivity : Activity() {

    private enum class Tab { SONGS, ALBUMS, ARTISTS }

    /** 一覧の1行（曲、またはアルバム・アーティストのまとまり） */
    private class Row(val title: String, val subtitle: String, val song: Song?, val group: List<Song>?, val artSong: Song?)

    private var tab = Tab.SONGS
    private var songs: List<Song> = emptyList()

    /** アルバムやアーティストを開いているときの中身 */
    private var opened: Pair<String, List<Song>>? = null
    private var rows: List<Row> = emptyList()

    private lateinit var bar: TopBar
    private lateinit var tabs: LinearLayout
    private lateinit var listView: ListView
    private lateinit var message: TextView
    private lateinit var mini: LinearLayout
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageView
    private val io = Executors.newSingleThreadExecutor()
    private val playerListener: () -> Unit = {
        updateMini()
        (listView.adapter as BaseAdapter).notifyDataSetChanged() // 再生中の曲を太字に
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        Player.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
        }
        root.padForSystemBars()

        bar = TopBar(this)
        bar.showBack(getString(R.string.back)) { goBack() }
        root.addView(bar)

        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        for (t in Tab.entries) {
            tabs.addView(label(getString(tabLabel(t)), 15f, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    tab = t
                    opened = null
                    render()
                    listView.setSelection(0)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        root.addView(tabs)

        message = label("", 16f, color = sub()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(80), dp(32), dp(16))
            visibility = View.GONE
        }
        root.addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        listView = ListView(this).apply {
            divider = null
            adapter = RowAdapter()
            setOnItemClickListener { _, _, pos, _ -> onRowClick(pos) }
        }
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ミニプレーヤー
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(4), dp(8))
            background = rounded(line(), 18)
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(this@MainActivity, NowPlayingActivity::class.java)) }
        }
        miniArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(bg())
            roundCorners(dp(8).toFloat())
        }
        mini.addView(miniArt, LinearLayout.LayoutParams(dp(44), dp(44)))
        val miniTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        miniTitle = label("", 15f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        miniSub = label("", 13f, color = sub()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        miniTexts.addView(miniTitle)
        miniTexts.addView(miniSub)
        mini.addView(miniTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        miniPlay = iconButton(R.drawable.ic_play, getString(R.string.play)) { Player.toggle() }
        mini.addView(miniPlay, LinearLayout.LayoutParams(dp(48), dp(48)))
        mini.addView(iconButton(R.drawable.ic_next, getString(R.string.next)) { Player.next() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(mini, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(8), dp(4), dp(8), dp(4))
        })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        if (!Perms.granted(this)) requestPermissions(Perms.needed(), REQ_PERM)
    }

    @Deprecated("Android 12 以前の「戻る」")
    override fun onBackPressed() {
        goBack()
    }

    override fun onStart() {
        super.onStart()
        Player.addListener(playerListener)
        updateMini()
    }

    override fun onStop() {
        Player.removeListener(playerListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (Perms.granted(this)) reload() else showDenied()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Perms.granted(this)) reload() else showDenied()
    }

    private fun goBack() {
        if (opened != null) {
            opened = null
            render()
        } else {
            finish()
        }
    }

    private fun showDenied() {
        songs = emptyList()
        render()
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
        message.setOnClickListener(null)
        io.execute {
            val list = MusicRepo.query(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                songs = list
                render()
            }
        }
    }

    private fun tabLabel(t: Tab) = when (t) {
        Tab.SONGS -> R.string.songs
        Tab.ALBUMS -> R.string.albums
        Tab.ARTISTS -> R.string.artists
    }

    private fun songCount(n: Int) = resources.getQuantityString(R.plurals.songs, n, n)

    private fun render() {
        val o = opened
        for (i in 0 until tabs.childCount) {
            val v = tabs.getChildAt(i) as TextView
            val selected = Tab.entries[i] == tab
            v.setTextColor(if (selected) bg() else fg())
            v.background = if (selected) pressable(fg(), 20) else pressable(bg(), 20)
        }
        tabs.visibility = if (o == null) View.VISIBLE else View.GONE
        bar.setSubtitle(null)
        if (o != null) {
            bar.titleView.text = o.first
            bar.setSubtitle(songCount(o.second.size))
            rows = o.second.map { Row(it.title, timeText(it.duration), it, null, it) }
        } else {
            bar.titleView.text = getString(R.string.app_name)
            rows = when (tab) {
                Tab.SONGS -> songs.map { Row(it.title, it.artist.ifEmpty { getString(R.string.unknown_artist) }, it, null, it) }
                Tab.ALBUMS -> songs.groupBy { it.albumId }.values
                    .map { list -> list.sortedWith(compareBy<Song>({ it.track }, { it.title })) }
                    .sortedWith(compareBy<List<Song>, String>(String.CASE_INSENSITIVE_ORDER) { it[0].album })
                    .map { list ->
                        Row(list[0].album.ifEmpty { getString(R.string.unknown_album) },
                            listOf(list[0].artist, songCount(list.size)).filter { it.isNotBlank() }.joinToString("  ·  "),
                            null, list, list[0])
                    }
                Tab.ARTISTS -> songs.groupBy { it.artist.ifEmpty { getString(R.string.unknown_artist) } }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .map { (name, list) -> Row(name, songCount(list.size), null, list, null) }
            }
        }
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        if (Perms.granted(this)) {
            message.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            message.text = getString(R.string.no_music)
        }
    }

    private fun onRowClick(pos: Int) {
        val r = rows[pos]
        val song = r.song
        val group = r.group
        when {
            song != null -> {
                val list = opened?.second ?: songs
                Player.play(list, list.indexOf(song))
            }
            group != null -> {
                opened = r.title to group
                render()
                listView.setSelection(0)
            }
        }
    }

    private fun updateMini() {
        val s = Player.current
        if (s == null) {
            mini.visibility = View.GONE
            return
        }
        mini.visibility = View.VISIBLE
        miniTitle.text = s.title
        miniSub.text = s.artist.ifEmpty { getString(R.string.unknown_artist) }
        miniPlay.setImageResource(if (Player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        miniPlay.contentDescription = getString(if (Player.isPlaying) R.string.pause else R.string.play)
        val art = Player.art
        if (art != null) miniArt.setImageBitmap(art) else miniArt.setImageDrawable(null)
    }

    private fun iconButton(icon: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(fg())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = desc
        ripple(true)
        setOnClickListener { onClick() }
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(line())
                    roundCorners(dp(8).toFloat())
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    addView(label("", 16f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(label("", 13f, color = sub()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                }
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val r = rows[position]
            val img = v.getChildAt(0) as ImageView
            val artSong = r.artSong
            img.visibility = if (artSong != null) View.VISIBLE else View.GONE
            img.setImageDrawable(null)
            if (artSong != null) {
                img.tag = artSong.albumId
                Art.load(this@MainActivity, artSong) { bmp -> if (img.tag == artSong.albumId) img.setImageBitmap(bmp) }
            }
            val texts = v.getChildAt(1) as LinearLayout
            val title = texts.getChildAt(0) as TextView
            title.text = r.title
            val playingNow = r.song != null && r.song.id == Player.current?.id
            title.typeface = if (playingNow) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            (texts.getChildAt(1) as TextView).text = r.subtitle
            return v
        }
    }

    companion object {
        private const val REQ_PERM = 1
    }
}
