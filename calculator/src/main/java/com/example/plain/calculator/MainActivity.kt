package com.example.plain.calculator

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var exprView: TextView
    private lateinit var resultView: TextView
    private var expr = StringBuilder()

    /** 「=」を押した直後か（次に数字を押したら新しい計算を始める） */
    private var justEvaluated = false

    private val operators = "+−×÷"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        savedInstanceState?.let {
            expr = StringBuilder(it.getString(KEY_EXPR) ?: "")
            justEvaluated = it.getBoolean(KEY_DONE)
        }

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(bg())
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        root.padForSystemBars()

        // 表示部分
        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(dp(12), dp(12), dp(12), dp(20))
        }
        exprView = label("", 56f).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            maxLines = 3
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setAutoSizeTextTypeUniformWithConfiguration(22, 64, 2, TypedValue.COMPLEX_UNIT_SP)
            setTextIsSelectable(true)
        }
        resultView = label("", 26f, color = sub()).apply {
            gravity = Gravity.END
            maxLines = 1
        }
        display.addView(exprView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        display.addView(resultView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ボタン部分
        val keys = listOf(
            listOf("C", "( )", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("⌫", "0", ".", "="),
        )
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (row in keys) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in row) line.addView(key(k), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            pad.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        if (landscape) {
            root.addView(display, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            root.addView(pad, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        } else {
            root.addView(display, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.6f))
        }
        setContentView(root)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_EXPR, expr.toString())
        outState.putBoolean(KEY_DONE, justEvaluated)
    }

    private fun key(k: String): View {
        val isOp = k in listOf("÷", "×", "−", "+", "%", "( )")
        val isEquals = k == "="
        val isDigit = k.length == 1 && (k[0].isDigit() || k == ".")
        return label(k, if (k == "( )") 24f else 30f, bold = isOp || isEquals, color = if (isEquals) bg() else fg()).apply {
            gravity = Gravity.CENTER
            background = when {
                isEquals -> pressable(fg(), 28)
                isDigit -> pressable(getColor(R.color.key), 28)
                isOp -> pressable(bg(), 28, line())
                else -> pressable(bg(), 28)
            }
            contentDescription = describe(k)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                press(k)
            }
            if (k == "⌫") setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                press("C")
                true
            }
        }.let { v ->
            // ボタン同士のすき間
            LinearLayout(this).apply {
                setPadding(dp(5), dp(5), dp(5), dp(5))
                addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
            }
        }
    }

    private fun describe(k: String): String = when (k) {
        "÷" -> getString(R.string.cd_divide)
        "×" -> getString(R.string.cd_multiply)
        "−" -> getString(R.string.cd_minus)
        "+" -> getString(R.string.cd_plus)
        "%" -> getString(R.string.cd_percent)
        "( )" -> getString(R.string.cd_paren)
        "=" -> getString(R.string.cd_equals)
        "C" -> getString(R.string.cd_clear)
        "⌫" -> getString(R.string.cd_backspace)
        "." -> getString(R.string.cd_point)
        else -> k
    }

    private fun press(k: String) {
        when (k) {
            "C" -> { expr.clear(); justEvaluated = false }
            "⌫" -> {
                if (justEvaluated) expr.clear() else if (expr.isNotEmpty()) expr.deleteCharAt(expr.length - 1)
                justEvaluated = false
            }
            "=" -> { evaluate(); return }
            "( )" -> paren()
            "%" -> percent()
            "." -> dot()
            "+", "−", "×", "÷" -> operator(k[0])
            else -> digit(k[0])
        }
        refresh()
    }

    private fun last(): Char? = expr.lastOrNull()

    /** いま入力中の数字（末尾の数字と小数点のかたまり） */
    private fun currentNumber(): String {
        var i = expr.length
        while (i > 0 && (expr[i - 1].isDigit() || expr[i - 1] == '.' || expr[i - 1] == 'E')) i--
        return expr.substring(i)
    }

    private fun closesValue(c: Char?) = c != null && (c.isDigit() || c == ')' || c == '%' || c == '.')

    private fun digit(d: Char) {
        if (justEvaluated) { expr.clear(); justEvaluated = false }
        if (expr.length >= MAX_LEN) return
        val c = last()
        if (c == ')' || c == '%') expr.append('×')
        if (currentNumber() == "0") expr.deleteCharAt(expr.length - 1)
        expr.append(d)
    }

    private fun dot() {
        if (justEvaluated) { expr.clear(); justEvaluated = false }
        val num = currentNumber()
        if (num.contains('.') || num.contains('E')) return
        if (num.isEmpty()) {
            if (last() == ')' || last() == '%') expr.append('×')
            expr.append("0.")
        } else {
            expr.append('.')
        }
    }

    private fun operator(o: Char) {
        justEvaluated = false
        val c = last()
        when {
            c == null || c == '(' -> if (o == '−') expr.append(o)
            c != null && c in operators -> {
                if (o == '−' && (c == '×' || c == '÷')) {
                    expr.append(o) // 「×−3」のような負の数
                } else {
                    expr.deleteCharAt(expr.length - 1)
                    val p = last()
                    if (p != null && p in operators) expr.deleteCharAt(expr.length - 1)
                    val q = last()
                    if (q == null || q == '(') {
                        if (o == '−') expr.append(o)
                    } else {
                        expr.append(o)
                    }
                }
            }
            else -> expr.append(o)
        }
    }

    private fun percent() {
        justEvaluated = false
        if (closesValue(last())) expr.append('%')
    }

    /** 1つのボタンで「(」と「)」を自動で使い分ける */
    private fun paren() {
        justEvaluated = false
        val open = expr.count { it == '(' } - expr.count { it == ')' }
        val c = last()
        if (open > 0 && closesValue(c)) {
            expr.append(')')
        } else {
            if (closesValue(c)) expr.append('×')
            expr.append('(')
        }
    }

    private fun refresh() {
        exprView.text = expr
        val hasOp = expr.any { it in operators || it == '%' } && expr.toString() != "−"
        resultView.text = if (hasOp && !justEvaluated) Calc.tryEval(expr.toString())?.let { "= $it" } ?: "" else ""
    }

    private fun evaluate() {
        if (expr.isEmpty()) return
        try {
            val r = Calc.format(Calc.eval(expr.toString()))
            expr = StringBuilder(r)
            justEvaluated = true
            exprView.text = expr
            resultView.text = ""
        } catch (e: DivideByZero) {
            resultView.text = getString(R.string.err_div_zero)
        } catch (e: Exception) {
            resultView.text = getString(R.string.err_syntax)
        }
    }

    companion object {
        private const val KEY_EXPR = "expr"
        private const val KEY_DONE = "done"
        private const val MAX_LEN = 200
    }
}
