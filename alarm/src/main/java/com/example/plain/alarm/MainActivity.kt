package com.example.plain.alarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var nextView: TextView
    private lateinit var banner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()

        val frame = FrameLayout(this).apply { setBackgroundColor(bg()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = TopBar(this)
        bar.titleView.text = getString(R.string.app_name)
        col.addView(bar)

        nextView = label("", 15f, color = sub()).apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        col.addView(nextView)

        banner = label("", 15f, true, bg()).apply {
            background = pressable(fg(), 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        col.addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(4), dp(16), dp(8))
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, 0, 0, dp(110))
            addView(list)
        }
        col.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(col)

        val add = label("+", 34f, false, bg()).apply {
            gravity = Gravity.CENTER
            background = pressable(fg(), 32)
            elevation = dp(4).toFloat()
            contentDescription = getString(R.string.add)
            setOnClickListener { edit(null) }
        }
        frame.addView(add, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(24), dp(24), dp(24), dp(24))
        })
        frame.padForSystemBars()
        setContentView(frame)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        Scheduler.rescheduleAll(this)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val alarms = AlarmStore.load(this)
        if (alarms.isEmpty()) {
            list.addView(label(getString(R.string.empty), 16f, color = sub()).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(80), dp(16), 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        for (a in alarms) {
            list.addView(row(a))
            list.addView(View(this).apply { setBackgroundColor(line()) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(dp(20), 0, dp(20), 0) })
        }
        val next = alarms.filter { it.enabled }.minOfOrNull { Scheduler.nextTime(it) }
        nextView.text = if (next == null) getString(R.string.no_alarm) else getString(R.string.next_in, untilText(next))
        updateBanner()
    }

    private fun row(a: Alarm): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(12), dp(14))
            ripple()
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(formatTime(this, a.hour, a.minute), 42f, color = if (a.enabled) fg() else sub()).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        val desc = listOf(Days.describe(this, a.days), a.label).filter { it.isNotBlank() }.joinToString("  ·  ")
        texts.addView(label(desc, 14f, color = sub()))
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(this)
        sw.isChecked = a.enabled
        sw.contentDescription = formatTime(this, a.hour, a.minute)
        sw.setOnCheckedChangeListener { _, on -> toggleAlarm(a, on) }
        r.addView(sw)

        r.setOnClickListener { edit(a) }
        r.setOnLongClickListener { confirmDelete(a); true }
        return r
    }

    private fun toggleAlarm(a: Alarm, on: Boolean) {
        val n = a.copy(enabled = on)
        AlarmStore.upsert(this, n)
        val ok = Scheduler.schedule(this, n)
        if (on) toast(if (ok) getString(R.string.set_in, untilText(Scheduler.nextTime(n))) else getString(R.string.need_exact))
        list.post { render() }
    }

    private fun confirmDelete(a: Alarm) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, formatTime(this, a.hour, a.minute)))
            .setPositiveButton(R.string.delete) { _, _ -> delete(a) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(a: Alarm) {
        Scheduler.cancel(this, a.id)
        AlarmStore.delete(this, a.id)
        render()
    }

    /** アラームの追加・編集ダイアログ */
    private fun edit(a: Alarm?) {
        val now = Calendar.getInstance()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val tp = TimePicker(this, null, 0, R.style.SpinnerTimePicker)
        tp.setIs24HourView(DateFormat.is24HourFormat(this))
        tp.hour = a?.hour ?: now.get(Calendar.HOUR_OF_DAY)
        tp.minute = a?.minute ?: now.get(Calendar.MINUTE)
        box.addView(tp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        box.addView(label(getString(R.string.repeat), 14f, color = sub()).apply { setPadding(0, dp(12), 0, dp(6)) })
        var days = a?.days ?: 0
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Days.shortNames().forEachIndexed { i, name ->
            val v = label(name, 14f, true)
            v.gravity = Gravity.CENTER
            val paint = {
                val on = (days and (1 shl i)) != 0
                v.setTextColor(if (on) bg() else fg())
                v.background = rounded(if (on) fg() else Color.TRANSPARENT, 20, fg(), 1)
            }
            paint()
            v.setOnClickListener {
                days = days xor (1 shl i)
                paint()
            }
            dayRow.addView(v, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        box.addView(dayRow)

        val labelEdit = EditText(this).apply {
            hint = getString(R.string.label_hint)
            setText(a?.label ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(40))
        }
        box.addView(labelEdit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        val vib = Switch(this).apply {
            text = getString(R.string.vibrate)
            textSize = 16f
            isChecked = a?.vibrate ?: true
            setPadding(0, dp(12), 0, dp(12))
        }
        box.addView(vib, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val dlg = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                tp.clearFocus()
                val n = Alarm(
                    id = a?.id ?: AlarmStore.newId(this),
                    hour = tp.hour,
                    minute = tp.minute,
                    days = days,
                    enabled = true,
                    label = labelEdit.text.toString().trim(),
                    vibrate = vib.isChecked,
                )
                AlarmStore.upsert(this, n)
                val ok = Scheduler.schedule(this, n)
                toast(if (ok) getString(R.string.set_in, untilText(Scheduler.nextTime(n))) else getString(R.string.need_exact))
                render()
            }
            .setNegativeButton(R.string.cancel, null)
        if (a != null) dlg.setNeutralButton(R.string.delete) { _, _ -> delete(a) }
        dlg.show()
    }

    private fun untilText(time: Long): String {
        val mins = ((time - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(0)
        val d = mins / (60 * 24)
        val h = (mins / 60) % 24
        val m = mins % 60
        return when {
            d > 0 -> getString(R.string.until_dhm, d, h, m)
            h > 0 -> getString(R.string.until_hm, h, m)
            else -> getString(R.string.until_m, m)
        }
    }

    /** アラームが確実に鳴るために必要な許可が足りないとき、上に案内を出す */
    private fun updateBanner() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val pkg = Uri.parse("package:$packageName")
        val problem: Pair<String, Intent>? = when {
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                getString(R.string.need_notif) to
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            Build.VERSION.SDK_INT >= 31 && !Scheduler.canSchedule(this) ->
                getString(R.string.need_exact) to Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() ->
                getString(R.string.need_fullscreen) to Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            else -> null
        }
        if (problem == null) {
            banner.visibility = View.GONE
            return
        }
        banner.text = problem.first
        banner.visibility = View.VISIBLE
        banner.setOnClickListener {
            try {
                startActivity(problem.second)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            }
        }
    }

    companion object {
        private const val REQ_NOTIF = 1
    }
}
