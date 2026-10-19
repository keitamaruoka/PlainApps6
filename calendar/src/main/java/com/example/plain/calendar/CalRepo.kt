package com.example.plain.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import java.util.Calendar
import java.util.TimeZone

/** カレンダー（「端末のみ」や、端末に登録済みのアカウントのカレンダー） */
data class Cal(val id: Long, val name: String, val account: String, val color: Int, val writable: Boolean, val isLocal: Boolean)

/** 画面に並べる予定1回分（繰り返しの予定は回ごとに1件） */
data class Instance(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val allDay: Boolean,
    val location: String,
    val color: Int,
)

/** 編集する予定 */
data class EventData(
    var id: Long? = null,
    var calendarId: Long = -1,
    var title: String = "",
    var location: String = "",
    var description: String = "",
    var allDay: Boolean = false,
    /** 終日の予定では「その日の0時（端末の時刻）」、それ以外は開始時刻 */
    var start: Long = 0,
    /** 終日の予定では「最終日の0時（端末の時刻）」、それ以外は終了時刻 */
    var end: Long = 0,
    var rrule: String? = null,
    /** 何分前に通知するか（null = 通知なし） */
    var reminder: Int? = null,
)

/**
 * Android標準のカレンダー保存領域（CalendarContract）を読み書きする。
 * 新しい予定は、どこにも同期されない「端末のみ」のカレンダーに保存するのが標準です。
 */
object CalRepo {
    private const val LOCAL_ACCOUNT = "PlainCalendar"
    private const val ACCESS_CONTRIBUTOR = 500
    private const val ACCESS_OWNER = 700
    private const val METHOD_ALERT = 1

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter("account_name", LOCAL_ACCOUNT)
        .appendQueryParameter("account_type", CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    fun calendars(ctx: Context): List<Cal> {
        val out = ArrayList<Cal>()
        val proj = arrayOf("_id", "calendar_displayName", "account_name", "account_type", "calendar_color", "calendar_access_level")
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(3) ?: ""
                val account = c.getString(2) ?: ""
                out += Cal(
                    id = c.getLong(0),
                    name = c.getString(1) ?: account,
                    account = account,
                    color = c.getInt(4),
                    writable = c.getInt(5) >= ACCESS_CONTRIBUTOR,
                    isLocal = type == CalendarContract.ACCOUNT_TYPE_LOCAL && account == LOCAL_ACCOUNT,
                )
            }
        }
        return out
    }

    /** 「端末のみ」のカレンダーを用意して、そのIDを返す */
    fun ensureLocal(ctx: Context, displayName: String): Long {
        calendars(ctx).firstOrNull { it.isLocal }?.let { return it.id }
        val v = ContentValues().apply {
            put("account_name", LOCAL_ACCOUNT)
            put("account_type", CalendarContract.ACCOUNT_TYPE_LOCAL)
            put("name", LOCAL_ACCOUNT)
            put("calendar_displayName", displayName)
            put("calendar_color", 0xFF222222.toInt())
            put("calendar_access_level", ACCESS_OWNER)
            put("ownerAccount", LOCAL_ACCOUNT)
            put("visible", 1)
            put("sync_events", 1)
            put("calendar_timezone", TimeZone.getDefault().id)
        }
        val uri = ctx.contentResolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), v)
        return uri?.let { ContentUris.parseId(it) } ?: -1
    }

    fun instances(ctx: Context, from: Long, to: Long): List<Instance> {
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from)
        ContentUris.appendId(b, to)
        val proj = arrayOf("event_id", "begin", "end", "title", "allDay", "eventLocation", "displayColor")
        val out = ArrayList<Instance>()
        ctx.contentResolver.query(b.build(), proj, "visible=1", null, "begin ASC")?.use { c ->
            while (c.moveToNext()) {
                out += Instance(
                    eventId = c.getLong(0),
                    begin = c.getLong(1),
                    end = c.getLong(2),
                    title = c.getString(3) ?: "",
                    allDay = c.getInt(4) != 0,
                    location = c.getString(5) ?: "",
                    color = c.getInt(6),
                )
            }
        }
        return out
    }

    fun load(ctx: Context, id: Long): EventData? {
        val proj = arrayOf("calendar_id", "title", "eventLocation", "description", "allDay", "dtstart", "dtend", "duration", "rrule")
        val e = ctx.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), proj, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val allDay = c.getInt(4) != 0
            val start = c.getLong(5)
            val end = if (!c.isNull(6)) c.getLong(6) else start + parseDuration(c.getString(7))
            EventData(
                id = id,
                calendarId = c.getLong(0),
                title = c.getString(1) ?: "",
                location = c.getString(2) ?: "",
                description = c.getString(3) ?: "",
                allDay = allDay,
                start = if (allDay) utcToLocalDay(start) else start,
                end = if (allDay) utcToLocalDay(end - DAY) else end,
                rrule = c.getString(8)?.takeIf { it.isNotBlank() },
            )
        } ?: return null
        ctx.contentResolver.query(CalendarContract.Reminders.CONTENT_URI, arrayOf("minutes"), "event_id=?", arrayOf(id.toString()), "minutes ASC")?.use { c ->
            if (c.moveToFirst()) e.reminder = c.getInt(0)
        }
        return e
    }

    /** 保存して予定のIDを返す */
    fun save(ctx: Context, e: EventData): Long {
        val start: Long
        val end: Long
        if (e.allDay) {
            // 終日の予定は、UTCの0時で保存するのがAndroidの決まり（終了は最終日の翌日0時）
            start = localDayToUtc(e.start)
            end = localDayToUtc(e.end) + DAY
        } else {
            start = e.start
            end = maxOf(e.end, e.start)
        }
        val v = ContentValues().apply {
            put("calendar_id", e.calendarId)
            put("title", e.title)
            put("eventLocation", e.location)
            put("description", e.description)
            put("allDay", if (e.allDay) 1 else 0)
            put("dtstart", start)
            put("eventTimezone", if (e.allDay) "UTC" else TimeZone.getDefault().id)
            put("hasAlarm", if (e.reminder != null) 1 else 0)
            val r = e.rrule
            if (r != null) {
                put("rrule", r)
                put("duration", if (e.allDay) "P${(end - start) / DAY}D" else "P${(end - start) / 1000}S")
                putNull("dtend")
            } else {
                put("dtend", end)
                putNull("rrule")
                putNull("duration")
            }
        }
        val cr = ctx.contentResolver
        val id = e.id
        val eventId = if (id == null) {
            val uri = cr.insert(CalendarContract.Events.CONTENT_URI, v) ?: return -1
            ContentUris.parseId(uri)
        } else {
            cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null)
            id
        }
        cr.delete(CalendarContract.Reminders.CONTENT_URI, "event_id=?", arrayOf(eventId.toString()))
        e.reminder?.let { min ->
            cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put("event_id", eventId)
                put("minutes", min)
                put("method", METHOD_ALERT)
            })
        }
        return eventId
    }

    fun delete(ctx: Context, id: Long) {
        ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
    }

    // ---- 日付の計算 ----

    const val DAY = 24 * 60 * 60 * 1000L

    /** 「UTCの0時」→ 同じ日付の「端末の時刻の0時」 */
    fun utcToLocalDay(utc: Long): Long {
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
        return Calendar.getInstance().apply {
            clear()
            set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    /** 「端末の時刻のその日」→ 同じ日付の「UTCの0時」 */
    fun localDayToUtc(local: Long): Long {
        val l = Calendar.getInstance().apply { timeInMillis = local }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    /** 日付を表す数字（例: 2026年10月17日 → 20261017） */
    fun dayKey(c: Calendar): Int = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)

    fun dayKey(millis: Long, tz: TimeZone = TimeZone.getDefault()): Int =
        dayKey(Calendar.getInstance(tz).apply { timeInMillis = millis })

    fun calendarOf(key: Int): Calendar = Calendar.getInstance().apply {
        clear()
        set(key / 10000, (key / 100) % 100 - 1, key % 100)
    }

    /** 予定が入っている日付（複数日にまたがる予定は、その全部の日） */
    fun daysOf(i: Instance): List<Int> {
        val out = ArrayList<Int>()
        if (i.allDay) {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = i.begin }
            while (c.timeInMillis < i.end && out.size < 62) {
                out += dayKey(c)
                c.add(Calendar.DAY_OF_MONTH, 1)
            }
            if (out.isEmpty()) out += dayKey(i.begin, TimeZone.getTimeZone("UTC"))
        } else {
            val c = Calendar.getInstance().apply { timeInMillis = i.begin }
            val last = dayKey(maxOf(i.begin, i.end - 1))
            while (out.size < 62) {
                val k = dayKey(c)
                out += k
                if (k >= last) break
                c.add(Calendar.DAY_OF_MONTH, 1)
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            }
        }
        return out
    }

    /** RFC5545 の期間（例: P3600S, PT1H, P1D）をミリ秒に */
    fun parseDuration(d: String?): Long {
        if (d.isNullOrBlank()) return 60 * 60 * 1000L
        val m = Regex("""[+-]?P(?:(\d+)W)?(?:(\d+)D)?(?:T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").matchEntire(d.trim())
            ?: return 60 * 60 * 1000L
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        return ((g(1) * 7 + g(2)) * 86400 + g(3) * 3600 + g(4) * 60 + g(5)) * 1000
    }
}
