package com.example.plain.music

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.PowerManager

/**
 * 音楽プレーヤー本体（アプリ内に1つだけ）。
 * 画面（MainActivity / NowPlayingActivity）と、通知を出すサービス（MusicService）の両方から使います。
 */
object Player {

    enum class Repeat { OFF, ALL, ONE }

    private lateinit var app: Context
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var errorsInRow = 0

    var queue: List<Song> = emptyList()
        private set

    /** 再生する順番（queue の番号の並び）。シャッフル時は並びが入れ替わる */
    private var order: List<Int> = emptyList()
    private var pos = 0

    val current: Song? get() = order.getOrNull(pos)?.let { queue.getOrNull(it) }

    var isPlaying = false
        private set
    var shuffle = false
        private set
    var repeat = Repeat.OFF
        private set
    var art: Bitmap? = null
        private set

    lateinit var session: MediaSession
        private set

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() {
        updateSession()
        listeners.toList().forEach { it() }
    }

    private val audioManager get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var resumeOnFocus = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val was = isPlaying
                pause()
                resumeOnFocus = was
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                mp?.setVolume(1f, 1f)
                if (resumeOnFocus) resume()
                resumeOnFocus = false
            }
        }
    }
    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val prefs = app.getSharedPreferences("player", Context.MODE_PRIVATE)
        shuffle = prefs.getBoolean("shuffle", false)
        repeat = Repeat.entries.getOrNull(prefs.getInt("repeat", 0)) ?: Repeat.OFF
        session = MediaSession(app, "PlainMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onStop() = stop()
            })
        }
    }

    private fun savePrefs() {
        app.getSharedPreferences("player", Context.MODE_PRIVATE).edit()
            .putBoolean("shuffle", shuffle)
            .putInt("repeat", repeat.ordinal)
            .apply()
    }

    // ---- 操作 ----

    /** list の index 番目から再生を始める */
    fun play(list: List<Song>, index: Int) {
        if (list.isEmpty()) return
        queue = list
        buildOrder(index.coerceIn(0, list.size - 1))
        errorsInRow = 0
        load(true)
    }

    private fun buildOrder(start: Int) {
        val all = queue.indices.toList()
        if (shuffle) {
            order = listOf(start) + all.filter { it != start }.shuffled()
            pos = 0
        } else {
            order = all
            pos = start
        }
    }

    private fun load(autoPlay: Boolean) {
        val s = current ?: return
        release()
        art = Art.peek(s)
        isPlaying = autoPlay
        val player = MediaPlayer()
        mp = player
        try {
            player.setAudioAttributes(attrs)
            player.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(app, s.uri)
            player.setOnPreparedListener {
                prepared = true
                errorsInRow = 0
                if (autoPlay && requestFocus()) it.start() else isPlaying = false
                changed()
            }
            player.setOnCompletionListener { onComplete() }
            player.setOnErrorListener { _, _, _ ->
                onError()
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            onError()
            return
        }
        Art.load(app, s) { bmp ->
            if (current?.albumId == s.albumId) {
                art = bmp
                changed()
            }
        }
        changed()
        if (autoPlay) startService()
    }

    private fun onError() {
        errorsInRow++
        if (errorsInRow >= queue.size) {
            stop() // どの曲も再生できない
        } else {
            next()
        }
    }

    private fun onComplete() {
        when {
            repeat == Repeat.ONE -> {
                mp?.seekTo(0)
                mp?.start()
            }
            pos < order.size - 1 -> {
                pos++
                load(true)
            }
            repeat == Repeat.ALL -> {
                pos = 0
                load(true)
            }
            else -> {
                // 最後の曲が終わった：最初の曲に戻して止める
                pos = 0
                load(false)
                abandonFocus()
            }
        }
    }

    fun toggle() = if (isPlaying) pause() else resume()

    fun resume() {
        val player = mp
        if (player == null || !prepared) {
            if (current != null && player == null) load(true)
            return
        }
        if (!requestFocus()) return
        player.start()
        isPlaying = true
        changed()
        startService()
    }

    fun pause() {
        if (prepared) mp?.pause()
        isPlaying = false
        changed()
    }

    fun next() {
        if (order.isEmpty()) return
        pos = if (pos + 1 < order.size) pos + 1 else 0
        load(true)
    }

    fun previous() {
        if (order.isEmpty()) return
        if (position() > 3000) {
            seekTo(0) // 3秒以上再生していたら曲の頭に戻る
            return
        }
        pos = if (pos > 0) pos - 1 else order.size - 1
        load(true)
    }

    fun seekTo(ms: Int) {
        if (prepared) mp?.seekTo(ms)
        changed()
    }

    fun stop() {
        release()
        isPlaying = false
        abandonFocus()
        queue = emptyList()
        order = emptyList()
        pos = 0
        session.isActive = false
        changed()
    }

    fun toggleShuffle() {
        val idx = order.getOrNull(pos)
        shuffle = !shuffle
        if (idx != null) {
            val all = queue.indices.toList()
            if (shuffle) {
                order = listOf(idx) + all.filter { it != idx }.shuffled()
                pos = 0
            } else {
                order = all
                pos = idx
            }
        }
        savePrefs()
        changed()
    }

    fun cycleRepeat() {
        repeat = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size]
        savePrefs()
        changed()
    }

    fun position(): Int = if (prepared) (mp?.currentPosition ?: 0) else 0
    fun duration(): Int = if (prepared) (mp?.duration ?: 0) else (current?.duration?.toInt() ?: 0)

    // ---- 内部 ----

    private fun release() {
        mp?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        mp = null
        prepared = false
    }

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun startService() {
        try {
            app.startForegroundService(Intent(app, MusicService::class.java))
        } catch (e: Exception) {
            // アプリが裏にいるときなど、起動できない場合がある（通知はすでに出ている）
        }
    }

    /** ロック画面・Bluetooth機器・通知のメディア操作に今の状態を伝える */
    private fun updateSession() {
        if (!::session.isInitialized) return
        val s = current
        if (s == null) {
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 1f).build())
            return
        }
        session.isActive = true
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration().toLong())
        art?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(meta.build())
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position().toLong(),
                    if (isPlaying) 1f else 0f,
                )
                .build(),
        )
    }
}
