package com.example.plain.alarm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * アラーム1件分。
 * days は曜日のビット（月=1, 火=2, 水=4 … 日=64）。0 なら「1回だけ」。
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val enabled: Boolean,
    val label: String,
    val vibrate: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("h", hour).put("m", minute).put("d", days)
        .put("on", enabled).put("label", label).put("vib", vibrate)

    companion object {
        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.getInt("h"),
            minute = o.getInt("m"),
            days = o.optInt("d", 0),
            enabled = o.optBoolean("on", true),
            label = o.optString("label", ""),
            vibrate = o.optBoolean("vib", true),
        )
    }
}

/**
 * アラームの保存場所。端末のロックを一度も解除していない再起動直後でも読めるよう、
 * 「端末保護ストレージ」に保存します（時刻とラベルだけで、ほかの情報は持ちません）。
 */
object AlarmStore {
    private fun prefs(ctx: Context): SharedPreferences =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Alarm> {
        val raw = prefs(ctx).getString("list", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { Alarm.fromJson(a.getJSONObject(it)) }
                .sortedWith(compareBy<Alarm>({ it.hour }, { it.minute }))
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, list: List<Alarm>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs(ctx).edit().putString("list", a.toString()).commit()
    }

    fun get(ctx: Context, id: Int): Alarm? = load(ctx).firstOrNull { it.id == id }

    fun upsert(ctx: Context, alarm: Alarm) = saveAll(ctx, load(ctx).filter { it.id != alarm.id } + alarm)

    fun delete(ctx: Context, id: Int) = saveAll(ctx, load(ctx).filter { it.id != id })

    fun newId(ctx: Context): Int {
        val p = prefs(ctx)
        val n = p.getInt("next", 1)
        p.edit().putInt("next", n + 1).commit()
        return n
    }
}

object Days {
    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000
    const val EVERY = 0b1111111

    /** Calendar の曜日 → ビットの位置（月=0 … 日=6） */
    fun indexOf(cal: Calendar): Int = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    /** 端末の言語に合わせた曜日の短い名前（月〜日） */
    fun shortNames(): List<String> {
        val sym = DateFormatSymbols.getInstance().shortWeekdays
        return listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
        ).map { sym[it] }
    }

    fun describe(ctx: Context, days: Int): String = when (days) {
        0 -> ctx.getString(R.string.once)
        EVERY -> ctx.getString(R.string.every_day)
        WEEKDAYS -> ctx.getString(R.string.weekdays)
        WEEKEND -> ctx.getString(R.string.weekends)
        else -> shortNames().filterIndexed { i, _ -> (days and (1 shl i)) != 0 }.joinToString(" ")
    }
}

fun formatTime(ctx: Context, hour: Int, minute: Int): String {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, hour)
    c.set(Calendar.MINUTE, minute)
    return android.text.format.DateFormat.getTimeFormat(ctx).format(c.time)
}
