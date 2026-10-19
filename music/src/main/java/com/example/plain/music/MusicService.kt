package com.example.plain.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.IBinder

/**
 * 音楽を裏で鳴らし続けるためのサービス。
 * 通知（ロック画面にも出る再生コントロール）を表示し、イヤホンが抜けたら一時停止します。
 */
class MusicService : Service() {

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1
        const val ACTION_TOGGLE = "com.example.plain.music.action.TOGGLE"
        const val ACTION_NEXT = "com.example.plain.music.action.NEXT"
        const val ACTION_PREV = "com.example.plain.music.action.PREV"
        const val ACTION_STOP = "com.example.plain.music.action.STOP"
    }

    private val listener: () -> Unit = { update(false) }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) Player.pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Player.init(this)
        Player.addListener(listener)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(noisy, filter)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> Player.toggle()
            ACTION_NEXT -> Player.next()
            ACTION_PREV -> Player.previous()
            ACTION_STOP -> Player.stop()
        }
        update(true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Player.removeListener(listener)
        try { unregisterReceiver(noisy) } catch (e: Exception) { }
        super.onDestroy()
    }

    /**
     * 通知を今の状態に合わせる。
     * startForegroundService で起動されたときは、必ず一度 startForeground を呼ぶ必要がある（forceForeground）。
     */
    private fun update(forceForeground: Boolean) {
        val song = Player.current
        if (song == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val n = buildNotification(song)
        if (Player.isPlaying || forceForeground) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } catch (e: Exception) {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
            }
        }
        if (!Player.isPlaying) {
            // 一時停止中は通知を残しつつ、スワイプで消せるようにする
            stopForeground(STOP_FOREGROUND_DETACH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        }
    }

    private fun pi(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, MusicService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(song: Song): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, NowPlayingActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val playing = Player.isPlaying
        fun action(icon: Int, title: Int, action: String, code: Int) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), getString(title), pi(action, code)).build()

        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(song.title)
            .setContentText(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" — "))
            .setContentIntent(open)
            .setDeleteIntent(pi(ACTION_STOP, 4))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setShowWhen(false)
            .addAction(action(R.drawable.ic_prev, R.string.previous, ACTION_PREV, 1))
            .addAction(
                if (playing) action(R.drawable.ic_pause, R.string.pause, ACTION_TOGGLE, 2)
                else action(R.drawable.ic_play, R.string.play, ACTION_TOGGLE, 2),
            )
            .addAction(action(R.drawable.ic_next, R.string.next, ACTION_NEXT, 3))
            .setStyle(Notification.MediaStyle().setMediaSession(Player.session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        Player.art?.let { b.setLargeIcon(it) }
        return b.build()
    }
}
