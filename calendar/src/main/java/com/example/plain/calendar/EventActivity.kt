package com.example.plain.calendar

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.DateFormat
import java.util.Calendar
import java.util.concurrent.Executors

/** 予定の追加・編集 */
class EventActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_DAY = "day"
    }

    private val repeats = listOf<String?>(null, "FREQ=DAILY", "FREQ=WEEKLY", "FREQ=MONTHLY", "FREQ=YEARLY")
    private val reminders = listOf<Int?>(null, 0, 5, 10, 30, 60, 24 * 60)

    private var e = EventData()
    private var calendars: List<Cal> = emptyList()
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var bar: TopBar
    private lateinit var title: EditText
    private lateinit var allDay: Switch
    private lateinit var startDate: TextView
    private lateinit var startTime: TextView
    private lateinit var endDate: TextView
    private lateinit var endTime: TextView
    private lateinit var repeatBtn: TextView
    private lateinit var reminderBtn: TextView
    private lateinit var calendarBtn: TextView
    private lateinit var location: EditText
    private lateinit var notes: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        if (!CalRepo.hasPermission(this)) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
        }
        root.padForSystemBars()
        bar = TopBar(this)
        bar.showBack(getString(R.string.back)) { finish() }
        bar.titleView.textSize = 18f
        root.addView(bar)

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(40))
        }
        title = field(getString(R.string.title_hint), 20f)
        form.addView(title)

        allDay = Switch(this).apply {
            text = getString(R.string.all_day)
            textSize = 16f
            setPadding(0, dp(14), 0, dp(14))
            setOnCheckedChangeListener { _, on ->
                e.allDay = on
                if (!on && e.end <= e.start) e.end = e.start + 60 * 60 * 1000L
                refresh()
            }
        }
        form.addView(allDay, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        startDate = chip { pickDate(true) }
        startTime = chip { pickTime(true) }
        endDate = chip { pickDate(false) }
        endTime = chip { pickTime(false) }
        form.addView(row(getString(R.string.starts), startDate, startTime))
        form.addView(row(getString(R.string.ends), endDate, endTime))

        repeatBtn = chip { chooseRepeat() }
        reminderBtn = chip { chooseReminder() }
        calendarBtn = chip { chooseCalendar() }
        form.addView(row(getString(R.string.repeat), repeatBtn))
        form.addView(row(getString(R.string.reminder), reminderBtn))
        form.addView(row(getString(R.string.calendar), calendarBtn))

        location = field(getString(R.string.location_hint), 16f)
        form.addView(location, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        notes = field(getString(R.string.notes_hint), 16f).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        form.addView(notes, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        root.addView(ScrollView(this).apply { addView(form) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val id = intent.getLongExtra(EXTRA_ID, -1)
        val day = intent.getIntExtra(EXTRA_DAY, 0)
        io.execute {
            val cals = CalRepo.calendars(this).filter { it.writable }
            val localId = CalRepo.ensureLocal(this, getString(R.string.local_calendar))
            val loaded = if (id > 0) CalRepo.load(this, id) else null
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                calendars = CalRepo.calendars(this).filter { it.writable }.ifEmpty { cals }
                e = loaded ?: newEvent(day, localId)
                bind(loaded != null)
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun newEvent(day: Int, calendarId: Long): EventData {
        val now = Calendar.getInstance()
        val c = if (day != 0) CalRepo.calendarOf(day) else Calendar.getInstance()
        if (day == 0 || CalRepo.dayKey(c) == CalRepo.dayKey(now)) {
            // 今日なら次のちょうどの時刻から
            c.timeInMillis = now.timeInMillis
            c.add(Calendar.HOUR_OF_DAY, 1)
        } else {
            c.set(Calendar.HOUR_OF_DAY, 10)
        }
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return EventData(calendarId = calendarId, start = c.timeInMillis, end = c.timeInMillis + 60 * 60 * 1000L, reminder = 10)
    }

    private fun bind(existing: Boolean) {
        bar.titleView.text = getString(if (existing) R.string.edit_event else R.string.new_event)
        bar.clearActions()
        if (existing) bar.action(getString(R.string.delete)) { confirmDelete() }
        bar.action(getString(R.string.save)) { save() }
        title.setText(e.title)
        location.setText(e.location)
        notes.setText(e.description)
        allDay.isChecked = e.allDay
        if (!existing) title.requestFocus()
        refresh()
    }

    private fun refresh() {
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val tf = android.text.format.DateFormat.getTimeFormat(this)
        startDate.text = df.format(e.start)
        endDate.text = df.format(e.end)
        startTime.text = tf.format(e.start)
        endTime.text = tf.format(e.end)
        val timeVis = if (e.allDay) View.GONE else View.VISIBLE
        startTime.visibility = timeVis
        endTime.visibility = timeVis
        repeatBtn.text = repeatLabel(e.rrule)
        reminderBtn.text = reminderLabel(e.reminder)
        calendarBtn.text = calendars.firstOrNull { it.id == e.calendarId }?.let { calLabel(it) } ?: "—"
    }

    // ---- 部品 ----

    private fun field(hint: String, size: Float) = EditText(this).apply {
        this.hint = hint
        textSize = size
        setTextColor(fg())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    private fun chip(onClick: () -> Unit) = label("", 15f).apply {
        background = pressable(line(), 12)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    private fun row(name: String, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(label(name, 15f, color = sub()), LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT))
        for (v in views) addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        })
    }

    // ---- 日時の選択 ----

    private fun pickDate(isStart: Boolean) {
        val c = Calendar.getInstance().apply { timeInMillis = if (isStart) e.start else e.end }
        DatePickerDialog(this, { _, y, m, d ->
            val t = Calendar.getInstance().apply { timeInMillis = if (isStart) e.start else e.end; set(y, m, d) }
            if (isStart) {
                val length = e.end - e.start
                e.start = t.timeInMillis
                e.end = e.start + length // 開始を動かしたら、長さはそのまま終了も動かす
            } else {
                e.end = maxOf(t.timeInMillis, e.start)
            }
            refresh()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime(isStart: Boolean) {
        val c = Calendar.getInstance().apply { timeInMillis = if (isStart) e.start else e.end }
        TimePickerDialog(this, { _, h, min ->
            val t = Calendar.getInstance().apply {
                timeInMillis = if (isStart) e.start else e.end
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
            }
            if (isStart) {
                val length = e.end - e.start
                e.start = t.timeInMillis
                e.end = e.start + length
            } else {
                e.end = if (t.timeInMillis < e.start) e.start + 60 * 60 * 1000L else t.timeInMillis
            }
            refresh()
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    // ---- 繰り返し・通知・カレンダー ----

    private fun repeatLabel(r: String?): String = when (r) {
        null -> getString(R.string.repeat_none)
        "FREQ=DAILY" -> getString(R.string.repeat_daily)
        "FREQ=WEEKLY" -> getString(R.string.repeat_weekly)
        "FREQ=MONTHLY" -> getString(R.string.repeat_monthly)
        "FREQ=YEARLY" -> getString(R.string.repeat_yearly)
        else -> getString(R.string.repeat_custom)
    }

    private fun chooseRepeat() {
        val labels = repeats.map { repeatLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.repeat)
            .setSingleChoiceItems(labels, repeats.indexOf(e.rrule)) { d, which ->
                e.rrule = repeats[which]
                refresh()
                d.dismiss()
            }
            .show()
    }

    private fun reminderLabel(m: Int?): String = when {
        m == null -> getString(R.string.reminder_none)
        m == 0 -> getString(R.string.reminder_at_start)
        m % (24 * 60) == 0 -> getString(R.string.reminder_days, m / (24 * 60))
        m % 60 == 0 -> getString(R.string.reminder_hours, m / 60)
        else -> getString(R.string.reminder_minutes, m)
    }

    private fun chooseReminder() {
        val labels = reminders.map { reminderLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.reminder)
            .setSingleChoiceItems(labels, reminders.indexOf(e.reminder)) { d, which ->
                e.reminder = reminders[which]
                refresh()
                d.dismiss()
            }
            .show()
    }

    private fun calLabel(c: Cal) = if (c.isLocal) getString(R.string.local_calendar) else "${c.name} (${c.account})"

    private fun chooseCalendar() {
        if (calendars.isEmpty()) return
        val labels = calendars.map { calLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.calendar)
            .setSingleChoiceItems(labels, calendars.indexOfFirst { it.id == e.calendarId }) { d, which ->
                e.calendarId = calendars[which].id
                refresh()
                d.dismiss()
            }
            .show()
    }

    // ---- 保存・削除 ----

    private fun save() {
        e.title = title.text.toString().trim()
        e.location = location.text.toString().trim()
        e.description = notes.text.toString().trim()
        if (e.title.isEmpty()) {
            toast(getString(R.string.need_title))
            return
        }
        if (e.calendarId < 0) {
            toast(getString(R.string.failed))
            return
        }
        val data = e.copy()
        io.execute {
            val ok = try { CalRepo.save(this, data) > 0 } catch (ex: Exception) { false }
            runOnUiThread {
                if (ok) finish() else toast(getString(R.string.failed))
            }
        }
    }

    private fun confirmDelete() {
        val id = e.id ?: return
        AlertDialog.Builder(this)
            .setMessage(if (e.rrule != null) R.string.delete_repeat_confirm else R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                io.execute {
                    try { CalRepo.delete(this, id) } catch (ex: Exception) { }
                    runOnUiThread { finish() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
