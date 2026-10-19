package com.example.plain.gallery

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView

/** 動画を再生する画面（Android標準の再生部品だけを使います） */
class VideoActivity : Activity() {

    private lateinit var video: VideoView
    private var position = 0

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        position = savedInstanceState?.getInt(KEY_POS) ?: 0

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        setContentView(root)

        val controller = MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)
        video.setVideoURI(uri)
        video.setOnPreparedListener {
            if (position > 0) video.seekTo(position)
            video.start()
            controller.show(2500)
        }
        video.setOnErrorListener { _, _, _ ->
            toast(getString(R.string.cannot_open))
            finish()
            true
        }
        root.setOnClickListener { controller.show() }

        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onPause() {
        super.onPause()
        if (::video.isInitialized) {
            position = video.currentPosition
            video.pause()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_POS, position)
    }

    companion object {
        private const val KEY_POS = "pos"
    }
}
