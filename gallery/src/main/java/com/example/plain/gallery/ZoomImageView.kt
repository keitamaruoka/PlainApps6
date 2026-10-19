package com.example.plain.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs

/**
 * ピンチ・ダブルタップで拡大でき、拡大していないときは左右にスワイプして前後の写真へ移れる画像表示。
 */
class ZoomImageView(ctx: Context) : ImageView(ctx) {

    /** 1回タップしたとき */
    var onTap: (() -> Unit)? = null

    /** 左右にスワイプしたとき（+1=次, -1=前）。移動できたら true を返す */
    var onSwipe: ((Int) -> Boolean)? = null

    private val mtx = Matrix()
    private val values = FloatArray(9)
    private var baseScale = 1f
    private var dragging = false
    private var flung = false

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setImage(b: Bitmap?) {
        setImageBitmap(b)
        reset()
    }

    private fun currentScale(): Float {
        mtx.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    fun isZoomed() = currentScale() > baseScale * 1.05f

    /** 画面に収まる大きさで中央に置く */
    fun reset() {
        val d = drawable
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (d == null || vw == 0f || vh == 0f || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        baseScale = minOf(vw / d.intrinsicWidth, vh / d.intrinsicHeight)
        mtx.reset()
        mtx.postScale(baseScale, baseScale)
        mtx.postTranslate((vw - d.intrinsicWidth * baseScale) / 2f, (vh - d.intrinsicHeight * baseScale) / 2f)
        imageMatrix = mtx
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    /** 画像が画面の外へ行きすぎないように位置を直す */
    private fun fix() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        mtx.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        mtx.postTranslate(dx, dy)
        imageMatrix = mtx
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            translationX = 0f
            dragging = false
            return drawable != null
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val cur = currentScale()
            val target = (cur * detector.scaleFactor).coerceIn(baseScale, baseScale * 8f)
            val f = target / cur
            mtx.postScale(f, f, detector.focusX, detector.focusY)
            fix()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (drawable == null) return false
            if (isZoomed()) {
                reset()
            } else {
                mtx.postScale(2.5f, 2.5f, e.x, e.y)
                fix()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaler.isInProgress || e2.pointerCount > 1) return false
            if (isZoomed()) {
                mtx.postTranslate(-distanceX, -distanceY)
                fix()
            } else if (dragging || abs(distanceX) > abs(distanceY)) {
                dragging = true
                translationX -= distanceX
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (isZoomed() || scaler.isInProgress) return false
            if (abs(velocityX) > abs(velocityY) && abs(velocityX) > 1200f) {
                finishSwipe(if (velocityX < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging && !flung) {
                when {
                    translationX < -width / 4f -> finishSwipe(1)
                    translationX > width / 4f -> finishSwipe(-1)
                    else -> animate().translationX(0f).setDuration(150).start()
                }
            }
            dragging = false
            flung = false
        }
        return true
    }

    private fun finishSwipe(dir: Int) {
        flung = true
        dragging = false
        val moved = onSwipe?.invoke(dir) ?: false
        if (moved) {
            // 新しい写真を反対側からすべり込ませる
            translationX = dir * width * 0.6f
            animate().translationX(0f).setDuration(180).start()
        } else {
            animate().translationX(0f).setDuration(150).start()
        }
    }
}
