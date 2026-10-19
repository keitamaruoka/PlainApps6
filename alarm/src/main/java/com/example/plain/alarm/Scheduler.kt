package com.example.plain.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** システムのアラーム機能（AlarmManager）への予約をまとめて扱う */
object Scheduler {
    const val ACTION_FIRE = "com.example.plain.alarm.action.FIRE"
    const val EXTRA_ID = "id"
    const val EXTRA_SNOOZE = "snooze"
    const val SNOOZE_MINUTES = 10
    private const val SNOOZE_OFFSET = 1_000_000

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 次に鳴る時刻（ミリ秒） */
    fun nextTime(a: Alarm, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        for (i in 0..7) {
            if (i > 0) c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, a.hour)
            c.set(Calendar.MINUTE, a.minute)
            if (c.timeInMillis <= now) continue
            if (a.days == 0 || (a.days and (1 shl Days.indexOf(c))) != 0) return c.timeInMillis
        }
        return c.timeInMillis
    }

    private fun firePi(ctx: Context, id: Int, snooze: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_SNOOZE, snooze)
        return PendingIntent.getBroadcast(
            ctx, if (snooze) id + SNOOZE_OFFSET else id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    /** 正確な時刻にアラームを鳴らす許可があるか（Android 12〜12L で確認が必要） */
    fun canSchedule(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    private fun setClock(ctx: Context, time: Long, pi: PendingIntent): Boolean {
        if (!canSchedule(ctx)) return false
        return try {
            // setAlarmClock はステータスバーに時計マークが出る「目覚まし」用の予約。省電力中でも時間どおりに鳴る
            am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(time, showPi(ctx)), pi)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** アラームを予約（オフなら取り消し）。予約できなければ false */
    fun schedule(ctx: Context, a: Alarm): Boolean {
        if (!a.enabled) {
            am(ctx).cancel(firePi(ctx, a.id, false))
            return true
        }
        return setClock(ctx, nextTime(a), firePi(ctx, a.id, false))
    }

    fun snooze(ctx: Context, id: Int) {
        setClock(ctx, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L, firePi(ctx, id, true))
    }

    fun cancel(ctx: Context, id: Int) {
        am(ctx).cancel(firePi(ctx, id, false))
        am(ctx).cancel(firePi(ctx, id, true))
    }

    fun rescheduleAll(ctx: Context) {
        AlarmStore.load(ctx).forEach { schedule(ctx, it) }
    }
}

/** 予約した時刻になったときに呼ばれる */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_FIRE) return
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val snooze = intent.getBooleanExtra(Scheduler.EXTRA_SNOOZE, false)
        val alarm = AlarmStore.get(ctx, id) ?: return
        if (!snooze) {
            if (alarm.days == 0) {
                AlarmStore.upsert(ctx, alarm.copy(enabled = false)) // 1回だけのアラームはオフにする
            } else {
                Scheduler.schedule(ctx, alarm) // 繰り返しなら次回を予約
            }
        }
        val svc = Intent(ctx, AlarmService::class.java)
            .setAction(AlarmService.ACTION_RING)
            .putExtra(Scheduler.EXTRA_ID, id)
        ctx.startForegroundService(svc)
    }
}

/** 再起動・時刻変更・アップデートのあとに、アラームを予約し直す */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Scheduler.rescheduleAll(ctx)
        }
    }
}
