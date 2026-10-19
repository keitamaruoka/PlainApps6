package com.example.plain.alarm

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextClock

/** アラームが鳴っているときの画面（ロック画面の上にも表示されます） */
class RingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        edgeToEdge()

        val id = AlarmService.ringingId
        if (id < 0) {
            finish()
            return
        }
        val alarm = AlarmStore.get(this, id)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(bg())
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        root.padForSystemBars()

        val clock = TextClock(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f)
            setTextColor(fg())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        root.addView(clock)
        val title = alarm?.label?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        root.addView(label(title, 24f, color = sub()).apply { gravity = Gravity.CENTER })

        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val snooze = label(getString(R.string.snooze_long, Scheduler.SNOOZE_MINUTES), 20f, true).apply {
            gravity = Gravity.CENTER
            background = pressable(bg(), 36, fg())
            setOnClickListener {
                startService(AlarmService.snoozeIntent(this@RingActivity))
                finish()
            }
        }
        val stop = label(getString(R.string.stop), 22f, true, bg()).apply {
            gravity = Gravity.CENTER
            background = pressable(fg(), 36)
            setOnClickListener {
                startService(AlarmService.stopIntent(this@RingActivity))
                finish()
            }
        }
        root.addView(snooze, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)))
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(16)))
        root.addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)))
        setContentView(root)

        AlarmService.onFinished = { if (!isFinishing) finish() }
    }

    override fun onDestroy() {
        AlarmService.onFinished = null
        super.onDestroy()
    }
}
