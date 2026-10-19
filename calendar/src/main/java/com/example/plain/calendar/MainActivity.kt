package com.example.plain.calendar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/** 月のカレンダーと、選んだ日の予定 */
class MainActivity : Activity() {

    private lateinit var bar: TopBar
    private lateinit var month: MonthView
    private lateinit var dayTitle: TextView
    private lateinit var listView: ListView
    private lateinit var message: TextView
    private var byDay: Map<Int, List<Instance>> = emptyMap()
    private var dayItems: List<Instance> = emptyList()
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()

        val frame = FrameLayout(this).apply { setBackgroundColor(bg()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bar = TopBar(this)
        bar.action("‹") { shiftMonth(-1) }.contentDescription = getString(R.string.prev_month)
        bar.action(getString(R.string.today)) { goToday() }
        bar.action("›") { shiftMonth(1) }.contentDescription = getString(R.string.next_month)
        bar.action("⋮") { showMenu(it) }.contentDescription = getString(R.string.menu)
        col.addView(bar)

        month = MonthView(this).apply {
            setPadding(dp(8), 0, dp(8), 0)
            onDayClick = { key -> select(key) }
            onSwipe = { dir -> shiftMonth(dir) }
        }
        col.addView(month, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.addView(View(this).apply { setBackgroundColor(line()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(4)
        })
        dayTitle = label("", 15f, true).apply { setPadding(dp(20), dp(12), dp(20), dp(4)) }
        col.addView(dayTitle)

        message = label("", 15f, color = sub()).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
        }
        col.addView(message)

        listView = ListView(this).apply {
            divider = null
            clipToPadding = false
            setPadding(0, 0, 0, dp(100))
            adapter = DayAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                startActivity(Intent(this@MainActivity, EventActivity::class.java).putExtra(EventActivity.EXTRA_ID, dayItems[pos].eventId))
            }
        }
        col.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(col)

        val add = label("+", 34f, false, bg()).apply {
            gravity = Gravity.CENTER
            background = pressable(fg(), 32)
            elevation = dp(4).toFloat()
            contentDescription = getString(R.string.new_event)
            setOnClickListener {
                if (CalRepo.hasPermission(this@MainActivity)) {
                    startActivity(Intent(this@MainActivity, EventActivity::class.java).putExtra(EventActivity.EXTRA_DAY, month.selected))
                }
            }
        }
        frame.addView(add, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(24), dp(24), dp(24), dp(24))
        })
        frame.padForSystemBars()
        setContentView(frame)

        val restored = savedInstanceState?.getInt(KEY_DAY) ?: 0
        val start = if (restored != 0) CalRepo.calendarOf(restored) else Calendar.getInstance()
        month.setMonth(start.get(Calendar.YEAR), start.get(Calendar.MONTH))
        month.selected = CalRepo.dayKey(start)
        updateTitle()

        if (!CalRepo.hasPermission(this)) {
            val perms = mutableListOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), REQ_PERM)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DAY, month.selected)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        reload()
    }

    private fun updateTitle() {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMMM")
        val c = Calendar.getInstance().apply { clear(); set(month.year, month.month, 1) }
        bar.titleView.text = SimpleDateFormat(pattern, Locale.getDefault()).format(c.time)
    }

    private fun shiftMonth(dir: Int) {
        val c = Calendar.getInstance().apply { clear(); set(month.year, month.month, 1); add(Calendar.MONTH, dir) }
        month.setMonth(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
        // 選択日も同じ「日」に寄せる（31日 → 30日などは月末に）
        val day = minOf(month.selected % 100, c.getActualMaximum(Calendar.DAY_OF_MONTH))
        c.set(Calendar.DAY_OF_MONTH, day)
        month.selected = CalRepo.dayKey(c)
        updateTitle()
        reload()
    }

    private fun goToday() {
        val c = Calendar.getInstance()
        month.setMonth(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
        month.selected = CalRepo.dayKey(c)
        updateTitle()
        reload()
    }

    private fun select(key: Int) {
        val c = CalRepo.calendarOf(key)
        if (c.get(Calendar.MONTH) != month.month || c.get(Calendar.YEAR) != month.year) {
            month.setMonth(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
            month.selected = key
            updateTitle()
            reload()
        } else {
            month.selected = key
            showDay()
        }
    }

    private fun reload() {
        if (!CalRepo.hasPermission(this)) {
            byDay = emptyMap()
            month.marks = emptyMap()
            showDay()
            message.visibility = View.VISIBLE
            message.text = getString(R.string.need_permission)
            message.setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            return
        }
        message.setOnClickListener(null)
        val from = month.gridStart().timeInMillis
        val to = from + 42 * CalRepo.DAY + CalRepo.DAY
        io.execute {
            val list = try { CalRepo.instances(this, from - CalRepo.DAY, to) } catch (e: Exception) { emptyList() }
            val map = HashMap<Int, MutableList<Instance>>()
            for (i in list) for (d in CalRepo.daysOf(i)) map.getOrPut(d) { mutableListOf() } += i
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                byDay = map
                month.marks = map.mapValues { (_, v) -> v.map { it.color } }
                showDay()
            }
        }
    }

    private fun showDay() {
        val c = CalRepo.calendarOf(month.selected)
        dayTitle.text = DateFormat.getDateInstance(DateFormat.FULL).format(c.time)
        dayItems = (byDay[month.selected] ?: emptyList()).sortedWith(compareBy<Instance>({ !it.allDay }, { it.begin }))
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        if (CalRepo.hasPermission(this)) {
            message.visibility = if (dayItems.isEmpty()) View.VISIBLE else View.GONE
            message.text = getString(R.string.no_events)
        }
    }

    private fun showMenu(anchor: View) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.notify_on_device).apply {
            isCheckable = true
            isChecked = prefs.getBoolean(KEY_NOTIFY, true)
        }
        m.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                val on = !prefs.getBoolean(KEY_NOTIFY, true)
                prefs.edit().putBoolean(KEY_NOTIFY, on).apply()
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM)
                }
            }
            true
        }
        m.show()
    }

    private fun timeText(i: Instance): String {
        if (i.allDay) return getString(R.string.all_day)
        val f = android.text.format.DateFormat.getTimeFormat(this)
        val sameDay = CalRepo.dayKey(i.begin) == CalRepo.dayKey(maxOf(i.begin, i.end - 1))
        return if (sameDay) "${f.format(i.begin)} – ${f.format(i.end)}"
        else {
            val d = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd"), Locale.getDefault())
            "${d.format(i.begin)} ${f.format(i.begin)} – ${d.format(i.end)} ${f.format(i.end)}"
        }
    }

    private inner class DayAdapter : BaseAdapter() {
        override fun getCount() = dayItems.size
        override fun getItem(position: Int) = dayItems[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(20), dp(10), dp(20), dp(10))
                addView(View(context), LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT))
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    addView(label("", 13f, color = sub()))
                    addView(label("", 16f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    addView(label("", 13f, color = sub()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                }
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val i = dayItems[position]
            v.getChildAt(0).background = rounded(i.color or 0xFF000000.toInt(), 2)
            val texts = v.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = timeText(i)
            (texts.getChildAt(1) as TextView).text = i.title.ifEmpty { getString(R.string.untitled) }
            val loc = texts.getChildAt(2) as TextView
            loc.text = i.location
            loc.visibility = if (i.location.isEmpty()) View.GONE else View.VISIBLE
            return v
        }
    }

    companion object {
        private const val REQ_PERM = 1
        private const val KEY_DAY = "day"
        const val KEY_NOTIFY = "notify"
    }
}
