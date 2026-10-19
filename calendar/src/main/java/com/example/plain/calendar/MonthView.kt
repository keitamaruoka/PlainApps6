package com.example.plain.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.text.DateFormatSymbols
import java.util.Calendar
import kotlin.math.abs

/** 1か月分のカレンダー。予定のある日には小さな点を付ける */
class MonthView(ctx: Context) : View(ctx) {

    var year = 2026
        private set
    var month = 0 // 0=1月
        private set

    var selected = 0
        set(v) { field = v; invalidate() }

    /** 日付 → その日の予定の色（点の表示用） */
    var marks: Map<Int, List<Int>> = emptyMap()
        set(v) { field = v; invalidate() }

    var onDayClick: ((Int) -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null

    private val firstDow = Calendar.getInstance().firstDayOfWeek
    private val density = resources.displayMetrics.density
    private val headerH = 28 * density

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 16 * resources.displayMetrics.scaledDensity
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12 * resources.displayMetrics.scaledDensity
        color = ctx.sub()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ctx.fg()
    }
    private val weekdayNames: List<String> = DateFormatSymbols.getInstance().shortWeekdays.let { names ->
        (0 until 7).map { names[(firstDow - 1 + it) % 7 + 1] }
    }

    fun setMonth(y: Int, m: Int) {
        year = y
        month = m
        invalidate()
    }

    /** 表示中のマスの最初の日（前月の日も含む） */
    fun gridStart(): Calendar = Calendar.getInstance().apply {
        clear()
        set(year, month, 1)
        val offset = (get(Calendar.DAY_OF_WEEK) - firstDow + 7) % 7
        add(Calendar.DAY_OF_MONTH, -offset)
    }

    private fun cellW() = width / 7f
    private fun cellH() = (height - headerH) / 6f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = (headerH + 6 * (w / 7f) * 0.78f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val cw = cellW()
        val ch = cellH()
        for (i in 0 until 7) canvas.drawText(weekdayNames[i], cw * i + cw / 2, headerH * 0.7f, headPaint)

        val today = CalRepo.dayKey(Calendar.getInstance())
        val c = gridStart()
        val fg = context.fg()
        val bg = context.bg()
        val sub = context.sub()
        val r = minOf(cw, ch) * 0.36f
        for (row in 0 until 6) {
            for (col in 0 until 7) {
                val key = CalRepo.dayKey(c)
                val cx = cw * col + cw / 2
                val cy = headerH + ch * row + ch * 0.42f
                val inMonth = c.get(Calendar.MONTH) == month
                if (key == selected) {
                    fill.color = fg
                    canvas.drawCircle(cx, cy, r, fill)
                } else if (key == today) {
                    canvas.drawCircle(cx, cy, r, ring)
                }
                numPaint.color = when {
                    key == selected -> bg
                    !inMonth -> (sub and 0x00FFFFFF) or 0x77000000
                    else -> fg
                }
                numPaint.typeface = if (key == today) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                canvas.drawText(c.get(Calendar.DAY_OF_MONTH).toString(), cx, cy - (numPaint.descent() + numPaint.ascent()) / 2, numPaint)

                // 予定の点（最大3つ）
                val colors = marks[key]
                if (!colors.isNullOrEmpty()) {
                    val n = minOf(colors.size, 3)
                    val dot = 2.6f * density
                    val gap = 7f * density
                    var x = cx - gap * (n - 1) / 2
                    val y = cy + r + 5 * density
                    for (k in 0 until n) {
                        fill.color = colors[k] or 0xFF000000.toInt()
                        canvas.drawCircle(x, y, dot, fill)
                        x += gap
                    }
                }
                c.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y < headerH) return false
            val col = (e.x / cellW()).toInt().coerceIn(0, 6)
            val row = ((e.y - headerH) / cellH()).toInt().coerceIn(0, 5)
            val c = gridStart()
            c.add(Calendar.DAY_OF_MONTH, row * 7 + col)
            onDayClick?.invoke(CalRepo.dayKey(c))
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityX) > abs(velocityY) && abs(velocityX) > 800) {
                onSwipe?.invoke(if (velocityX < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
