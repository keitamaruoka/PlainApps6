package com.example.plain.music

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** 再生中の曲を大きく表示する画面 */
class NowPlayingActivity : Activity() {

    private lateinit var art: ImageView
    private lateinit var artPlaceholder: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeAll: TextView
    private lateinit var playBtn: ImageView
    private lateinit var shuffleBtn: ImageView
    private lateinit var repeatBtn: ImageView
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        Player.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
            setPadding(0, 0, 0, dp(16))
        }
        root.padForSystemBars()

        val bar = TopBar(this)
        bar.showBack(getString(R.string.back)) { finish() }
        bar.titleView.text = getString(R.string.now_playing)
        bar.titleView.textSize = 17f
        root.addView(bar)

        // ジャケット
        val artBox = FrameLayout(this).apply {
            background = rounded(line(), 20)
            roundCorners(dp(20).toFloat())
        }
        artPlaceholder = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            imageTintList = ColorStateList.valueOf(sub())
        }
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artBox.addView(artPlaceholder, FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER))
        artBox.addView(art, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val artWrap = object : FrameLayout(this) {
            // 空いている場所いっぱいに、正方形で置く
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = View.MeasureSpec.getSize(widthMeasureSpec)
                val h = View.MeasureSpec.getSize(heightMeasureSpec)
                val side = minOf(w, h)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                artBox.measure(
                    View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
                )
            }

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                val l = (width - artBox.measuredWidth) / 2
                val t = (height - artBox.measuredHeight) / 2
                artBox.layout(l, t, l + artBox.measuredWidth, t + artBox.measuredHeight)
            }
        }
        artWrap.setPadding(dp(28), dp(8), dp(28), dp(8))
        artWrap.addView(artBox)
        root.addView(artWrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dp(28), dp(8), dp(28), dp(8))
        })

        title = label("", 22f, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        subtitle = label("", 15f, color = sub()).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // シークバー
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(fg())
            thumbTintList = ColorStateList.valueOf(fg())
            progressBackgroundTintList = ColorStateList.valueOf(line())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeNow.text = timeText(progress.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    userSeeking = false
                    Player.seekTo(sb.progress)
                }
            })
        }
        root.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(8), dp(12), 0)
        })
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), 0, dp(28), 0)
        }
        timeNow = label("0:00", 13f, color = sub())
        timeAll = label("0:00", 13f, color = sub()).apply { gravity = Gravity.END }
        times.addView(timeNow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(timeAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(times)

        // 操作ボタン
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        shuffleBtn = icon(R.drawable.ic_shuffle, R.string.shuffle) { Player.toggleShuffle() }
        val prev = icon(R.drawable.ic_prev, R.string.previous) { Player.previous() }
        playBtn = icon(R.drawable.ic_play, R.string.play) { Player.toggle() }.apply {
            imageTintList = ColorStateList.valueOf(bg())
            background = pressable(fg(), 40)
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        val next = icon(R.drawable.ic_next, R.string.next) { Player.next() }
        repeatBtn = icon(R.drawable.ic_repeat, R.string.repeat) { Player.cycleRepeat() }
        controls.addView(shuffleBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(prev, LinearLayout.LayoutParams(dp(60), dp(60)))
        controls.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        controls.addView(playBtn, LinearLayout.LayoutParams(dp(80), dp(80)))
        controls.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        controls.addView(next, LinearLayout.LayoutParams(dp(60), dp(60)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(repeatBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        Player.addListener(listener)
        render()
        handler.post(tick)
    }

    override fun onStop() {
        Player.removeListener(listener)
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun icon(res: Int, desc: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(fg())
        setPadding(dp(14), dp(14), dp(14), dp(14))
        contentDescription = getString(desc)
        ripple(true)
        setOnClickListener { onClick() }
    }

    private fun render() {
        val s = Player.current
        if (s == null) {
            finish()
            return
        }
        title.text = s.title
        subtitle.text = listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" — ")
        val bmp = Player.art
        art.setImageBitmap(bmp)
        artPlaceholder.visibility = if (bmp == null) View.VISIBLE else View.GONE
        playBtn.setImageResource(if (Player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = getString(if (Player.isPlaying) R.string.pause else R.string.play)
        shuffleBtn.alpha = if (Player.shuffle) 1f else 0.35f
        repeatBtn.alpha = if (Player.repeat == Player.Repeat.OFF) 0.35f else 1f
        repeatBtn.setImageResource(if (Player.repeat == Player.Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        title.typeface = Typeface.DEFAULT_BOLD
        updateProgress()
    }

    private fun updateProgress() {
        if (userSeeking) return
        val d = Player.duration()
        seek.max = maxOf(d, 1)
        seek.progress = Player.position()
        timeNow.text = timeText(Player.position().toLong())
        timeAll.text = timeText(d.toLong())
    }
}
