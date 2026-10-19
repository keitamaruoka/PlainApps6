package com.example.plain.video

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView

/**
 * 動画の再生画面。
 * 画面をタップで操作ボタンの表示/非表示、左右をダブルタップで10秒戻る/進む。
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_TITLE = "title"
        private const val KEY_POS = "pos"
        private const val KEY_SPEED = "speed"
        private val SPEEDS = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
    }

    private lateinit var video: VideoView
    private lateinit var controls: FrameLayout
    private lateinit var playBtn: ImageView
    private lateinit var seek: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeAll: TextView
    private lateinit var speedBtn: TextView
    private var player: MediaPlayer? = null
    private var key = ""
    private var startPos = 0
    private var speedIndex = 0
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { setControlsVisible(false) }
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        edgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        key = uri.toString()
        startPos = savedInstanceState?.getInt(KEY_POS) ?: Resume.get(this, key)
        speedIndex = savedInstanceState?.getInt(KEY_SPEED) ?: 0

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 操作ボタン
        controls = FrameLayout(this).apply { setBackgroundColor(0x66000000) }
        val top = TopBar(this, Color.WHITE)
        top.showBack(getString(R.string.back)) { finish() }
        top.titleView.text = intent.getStringExtra(EXTRA_TITLE) ?: uri.lastPathSegment ?: ""
        top.titleView.textSize = 17f
        speedBtn = top.action("1x") { cycleSpeed() }
        speedBtn.contentDescription = getString(R.string.speed)
        top.padForSystemBars(bottom = false)
        controls.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        playBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_pause)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = pressable(0x55000000, 40)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            contentDescription = getString(R.string.play_pause)
            setOnClickListener { togglePlay() }
        }
        controls.addView(playBtn, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        timeNow = label("0:00", 13f, true, Color.WHITE)
        timeAll = label("0:00", 13f, true, Color.WHITE)
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeNow.text = timeText(progress.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar) {
                    userSeeking = true
                    handler.removeCallbacks(hideControls)
                }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    userSeeking = false
                    video.seekTo(sb.progress)
                    scheduleHide()
                }
            })
        }
        bottom.addView(timeNow)
        bottom.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(timeAll)
        bottom.padForSystemBars(top = false)
        controls.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // タップ・ダブルタップ
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControlsVisible(controls.visibility != View.VISIBLE)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val forward = e.x > root.width / 2f
                val target = (video.currentPosition + if (forward) 10_000 else -10_000).coerceIn(0, maxOf(0, video.duration))
                video.seekTo(target)
                toast(getString(if (forward) R.string.skip_forward else R.string.skip_back))
                return true
            }
        })
        root.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
        controls.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }

        video.setOnPreparedListener { mp ->
            player = mp
            seek.max = mp.duration
            timeAll.text = timeText(mp.duration.toLong())
            // 横長の動画は横向きで表示
            requestedOrientation = if (mp.videoWidth > mp.videoHeight) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            if (startPos > 0) {
                video.seekTo(startPos)
                if (savedInstanceState == null) toast(getString(R.string.resume_from, timeText(startPos.toLong())))
            }
            play()
        }
        video.setOnCompletionListener {
            Resume.put(this, key, 0, 0)
            startPos = 0
            playBtn.setImageResource(R.drawable.ic_play)
            setControlsVisible(true)
        }
        video.setOnErrorListener { _, _, _ ->
            toast(getString(R.string.cannot_play))
            finish()
            true
        }
        video.setVideoURI(uri)
        updateSpeedLabel()
        setControlsVisible(true)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        if (::video.isInitialized && player != null) {
            startPos = video.currentPosition
            Resume.put(this, key, video.currentPosition, video.duration)
            video.pause()
            playBtn.setImageResource(R.drawable.ic_play)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_POS, startPos)
        outState.putInt(KEY_SPEED, speedIndex)
    }

    private fun play() {
        video.start()
        applySpeed()
        playBtn.setImageResource(R.drawable.ic_pause)
        scheduleHide()
    }

    private fun togglePlay() {
        if (video.isPlaying) {
            video.pause()
            playBtn.setImageResource(R.drawable.ic_play)
            handler.removeCallbacks(hideControls)
        } else {
            play()
        }
    }

    /** 再生速度。停止中に変えると勝手に再生が始まるため、再生中だけ反映する */
    private fun applySpeed() {
        val mp = player ?: return
        if (!video.isPlaying) return
        try {
            mp.playbackParams = mp.playbackParams.setSpeed(SPEEDS[speedIndex])
        } catch (e: Exception) {
            // 速度変更に対応していない動画もある
        }
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        updateSpeedLabel()
        applySpeed()
        scheduleHide()
    }

    private fun updateSpeedLabel() {
        val s = SPEEDS[speedIndex]
        speedBtn.text = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "x"
    }

    private fun updateProgress() {
        if (player == null || userSeeking) return
        seek.progress = video.currentPosition
        timeNow.text = timeText(video.currentPosition.toLong())
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 3000)
    }

    @Suppress("DEPRECATION")
    private fun setControlsVisible(visible: Boolean) {
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && video.isPlaying) scheduleHide()
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (visible) {
                c.show(WindowInsets.Type.systemBars())
            } else {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (visible) 0 else View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
