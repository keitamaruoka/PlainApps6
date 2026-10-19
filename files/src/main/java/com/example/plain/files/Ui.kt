package com.example.plain.files

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// 画面づくりの小さな道具箱。4つのアプリで同じものを使っています。

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.fg(): Int = getColor(R.color.fg)
fun Context.bg(): Int = getColor(R.color.bg)
fun Context.sub(): Int = getColor(R.color.sub)
fun Context.line(): Int = getColor(R.color.line)

fun Context.toast(msg: CharSequence) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/** 画面の端まで描画し、ステータスバーなどの分は各画面で余白を取る（Android 15以降の仕様に全バージョンで合わせる） */
@Suppress("DEPRECATION")
fun Activity.edgeToEdge() {
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    } else {
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}

class Bars(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Suppress("DEPRECATION")
fun WindowInsets.bars(): Bars =
    if (Build.VERSION.SDK_INT >= 30) {
        val i = getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        Bars(i.left, i.top, i.right, i.bottom)
    } else {
        Bars(systemWindowInsetLeft, systemWindowInsetTop, systemWindowInsetRight, systemWindowInsetBottom)
    }

/** ステータスバー・ナビゲーションバーと重ならないように余白を足す */
fun View.padForSystemBars(top: Boolean = true, bottom: Boolean = true) {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val s = insets.bars()
        v.setPadding(l + s.left, t + (if (top) s.top else 0), r + s.right, b + (if (bottom) s.bottom else 0))
        insets
    }
    requestApplyInsets()
}

fun Context.label(
    s: CharSequence = "",
    sizeSp: Float = 16f,
    bold: Boolean = false,
    color: Int = fg(),
): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.rounded(fill: Int, radiusDp: Number, strokeColor: Int? = null, strokeDp: Number = 1.5f): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

/** 押すと波紋が広がる背景 */
fun Context.pressable(fill: Int, radiusDp: Number, strokeColor: Int? = null): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x40888888), rounded(fill, radiusDp, strokeColor), rounded(Color.WHITE, radiusDp))

/** リスト行などに使う、標準の押したときの効果 */
fun View.ripple(borderless: Boolean = false) {
    val tv = TypedValue()
    val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
    context.theme.resolveAttribute(attr, tv, true)
    background = context.getDrawable(tv.resourceId)
    isClickable = true
    isFocusable = true
}

/** 画面上部のタイトルバー */
class TopBar(ctx: Context, private val color: Int = ctx.fg()) : LinearLayout(ctx) {
    val titleView: TextView
    val subtitleView: TextView
    private val actions = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(60)
        setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        val col = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(8), ctx.dp(6))
        }
        titleView = ctx.label("", 22f, true, color).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = ctx.label("", 13f, false, if (color == ctx.fg()) ctx.sub() else color).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            visibility = GONE
        }
        col.addView(titleView)
        col.addView(subtitleView)
        addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(actions)
    }

    fun setSubtitle(s: CharSequence?) {
        subtitleView.text = s
        subtitleView.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
    }

    /** 左端に「戻る」ボタンを付ける */
    fun showBack(desc: String, onClick: () -> Unit): TextView {
        val b = button("←", 24f)
        b.contentDescription = desc
        b.setOnClickListener { onClick() }
        addView(b, 0)
        return b
    }

    /** 右側に文字のボタンを足す */
    fun action(text: String, onClick: (View) -> Unit): TextView {
        val b = button(text, 16f)
        b.setOnClickListener(onClick)
        actions.addView(b)
        return b
    }

    fun clearActions() = actions.removeAllViews()

    private fun button(text: String, size: Float): TextView = context.label(text, size, true, color).apply {
        gravity = Gravity.CENTER
        minWidth = context.dp(48)
        minHeight = context.dp(48)
        setPadding(context.dp(10), 0, context.dp(10), 0)
        ripple(true)
    }
}
