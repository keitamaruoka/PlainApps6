package com.example.plain.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * アラーム音を鳴らし続けるサービス。
 * 画面がオフのときは通知の「全画面表示」で RingActivity を開き、使用中は通知に「停止」「スヌーズ」を出します。
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_RING = "com.example.plain.alarm.action.RING"
        const val ACTION_STOP = "com.example.plain.alarm.action.STOP"
        const val ACTION_SNOOZE = "com.example.plain.alarm.action.SNOOZE"
        const val CHANNEL = "alarm"
        private const val NOTIF_ID = 1
        private const val TIMEOUT_MS = 10 * 60_000L // 10分鳴っても止められなければ自動で止める

        /** 鳴っているアラームのID（鳴っていなければ -1） */
        @Volatile
        var ringingId: Int = -1
            private set

        /** 鳴り終わったときに RingActivity を閉じるための合図 */
        var onFinished: (() -> Unit)? = null

        fun stopIntent(ctx: Context): Intent = Intent(ctx, AlarmService::class.java).setAction(ACTION_STOP)
        fun snoozeIntent(ctx: Context): Intent = Intent(ctx, AlarmService::class.java).setAction(ACTION_SNOOZE)
    }

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finishRinging() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> ring(intent.getIntExtra(Scheduler.EXTRA_ID, -1))
            ACTION_SNOOZE -> {
                if (ringingId >= 0) Scheduler.snooze(this, ringingId)
                finishRinging()
            }
            else -> finishRinging()
        }
        return START_NOT_STICKY
    }

    private fun ring(id: Int) {
        val alarm = AlarmStore.get(this, id)
        try {
            startForeground(NOTIF_ID, buildNotification(alarm), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        ringingId = id
        stopSound()
        startSound(alarm?.vibrate ?: true)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private val audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun tryPlay(uri: Uri?): MediaPlayer? {
        if (uri == null) return null
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(audioAttrs)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            mp.release()
            null
        }
    }

    private fun startSound(vibrate: Boolean) {
        // 端末で設定されているアラーム音 → 標準のアラーム音 → 着信音 → それも無理なら電子音
        player = tryPlay(RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM))
            ?: tryPlay(Settings.System.DEFAULT_ALARM_ALERT_URI)
            ?: tryPlay(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        if (player == null) {
            tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME).also {
                    it.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)
                }
            } catch (e: Exception) {
                null
            }
        }
        if (vibrate) {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator = v
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 700), 0), audioAttrs)
        }
    }

    private fun stopSound() {
        player?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        player = null
        tone?.let {
            it.stopTone()
            it.release()
        }
        tone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun finishRinging() {
        handler.removeCallbacks(timeout)
        stopSound()
        ringingId = -1
        onFinished?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopSound()
        ringingId = -1
        super.onDestroy()
    }

    private fun buildNotification(alarm: Alarm?): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // 音はこのサービスが自分で鳴らす
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)

        val ringIntent = Intent(this, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val full = PendingIntent.getActivity(this, 1, ringIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getService(this, 3, snoozeIntent(this), PendingIntent.FLAG_IMMUTABLE)

        val title = alarm?.label?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        val time = alarm?.let { formatTime(this, it.hour, it.minute) } ?: ""
        val noIcon: Icon? = null
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(time)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(Notification.Action.Builder(noIcon, getString(R.string.snooze_short, Scheduler.SNOOZE_MINUTES), snooze).build())
            .addAction(Notification.Action.Builder(noIcon, getString(R.string.stop), stop).build())
            .build()
    }
}
