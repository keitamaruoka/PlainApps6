package com.example.plain.calendar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.TimeZone

/**
 * 予定の通知。時間になると Android のカレンダー機能がこのアプリに知らせてくるので、通知を出す。
 * （Googleカレンダーなど他のカレンダーアプリも入っている場合は、メニューから止められます）
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL = "reminders"
        private const val STATE_SCHEDULED = 0
        private const val STATE_FIRED = 1
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != CalendarContract.ACTION_EVENT_REMINDER) return
        if (!ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(MainActivity.KEY_NOTIFY, true)) return
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH))

        val now = System.currentTimeMillis()
        val proj = arrayOf("_id", "event_id", "title", "begin", "end", "allDay", "eventLocation")
        val cr = ctx.contentResolver
        try {
            cr.query(
                CalendarContract.CalendarAlerts.CONTENT_URI, proj,
                "state=? AND alarmTime<=?", arrayOf(STATE_SCHEDULED.toString(), now.toString()), "begin ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val alertId = c.getLong(0)
                    val eventId = c.getLong(1)
                    val title = c.getString(2)?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.untitled)
                    val begin = c.getLong(3)
                    val allDay = c.getInt(5) != 0
                    val loc = c.getString(6) ?: ""
                    val time = if (allDay) {
                        val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                        df.timeZone = TimeZone.getTimeZone("UTC")
                        df.format(begin) + " " + ctx.getString(R.string.all_day)
                    } else {
                        android.text.format.DateFormat.getTimeFormat(ctx).format(begin)
                    }
                    val open = PendingIntent.getActivity(
                        ctx, alertId.toInt(),
                        Intent(ctx, EventActivity::class.java).putExtra(EventActivity.EXTRA_ID, eventId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val n = Notification.Builder(ctx, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_calendar)
                        .setContentTitle(title)
                        .setContentText(listOf(time, loc).filter { it.isNotBlank() }.joinToString("  ·  "))
                        .setCategory(Notification.CATEGORY_EVENT)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                    if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
                        android.os.Build.VERSION.SDK_INT < 33
                    ) {
                        nm.notify(alertId.toInt(), n)
                    }
                    // 通知済みにする（同じ通知を何度も出さないため）
                    cr.update(
                        ContentUris.withAppendedId(CalendarContract.CalendarAlerts.CONTENT_URI, alertId),
                        ContentValues().apply { put("state", STATE_FIRED) }, null, null,
                    )
                }
            }
        } catch (e: Exception) {
            // カレンダーが読めない場合は何もしない
        }
    }
}
