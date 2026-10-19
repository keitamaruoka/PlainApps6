package com.example.plain.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** 0で割ったとき */
class DivideByZero : ArithmeticException("divide by zero")

private val MC = MathContext(34, RoundingMode.HALF_EVEN)
private val HUNDRED = BigDecimal(100)

/**
 * 電卓の計算エンジン。
 * 小数の誤差（0.1+0.2=0.30000000000000004 のようなもの）が出ないよう、BigDecimal で計算します。
 * 使える記号: + − × ÷ % ( )   閉じ忘れた括弧は自動で閉じたとみなします。
 */
object Calc {
    fun eval(s: String): BigDecimal = Parser(s).parseAll()

    fun tryEval(s: String): String? = try {
        format(eval(s))
    } catch (e: Exception) {
        null
    }

    /** 表示用の文字列にする。大きすぎる・小さすぎる数は指数表記（1.5E20 など） */
    fun format(v: BigDecimal): String {
        val r = v.round(MathContext(15, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        if (r.signum() == 0) return "0"
        val abs = r.abs()
        val body = if (abs >= BigDecimal("1E15") || abs < BigDecimal("1E-9")) {
            val digits = abs.unscaledValue().toString()
            val exp = digits.length - 1 - abs.scale()
            val mantissa = if (digits.length > 1) "${digits[0]}.${digits.substring(1)}" else digits
            "${mantissa}E$exp"
        } else {
            abs.toPlainString()
        }
        return (if (r.signum() < 0) "−" else "") + body.replace("E-", "E−")
    }
}

private class Parser(private val s: String) {
    private var i = 0

    fun parseAll(): BigDecimal {
        if (s.isEmpty()) throw IllegalArgumentException("empty")
        val v = expr()
        if (i != s.length) throw IllegalArgumentException("unexpected '${s[i]}'")
        return v
    }

    private fun peek(): Char? = if (i < s.length) s[i] else null

    private fun expr(): BigDecimal {
        var v = term()
        while (true) {
            when (peek()) {
                // 「50+10%」は 50 の 10% を足す（=55）。スマホの電卓と同じ考え方
                '+' -> { i++; val t = term(); v = v.add(if (termIsPercent) v.multiply(t, MC) else t, MC) }
                '−', '-' -> { i++; val t = term(); v = v.subtract(if (termIsPercent) v.multiply(t, MC) else t, MC) }
                else -> return v
            }
        }
    }

    /** 直前に読んだ項が「10%」のような百分率だけだったか */
    private var termIsPercent = false
    private var lastWasPercent = false

    private fun term(): BigDecimal {
        var v = unary()
        termIsPercent = lastWasPercent
        while (true) {
            when (peek()) {
                '×', '*' -> { i++; termIsPercent = false; v = v.multiply(unary(), MC) }
                '÷', '/' -> {
                    i++
                    termIsPercent = false
                    val d = unary()
                    if (d.signum() == 0) throw DivideByZero()
                    v = v.divide(d, MC)
                }
                else -> return v
            }
        }
    }

    private fun unary(): BigDecimal = when (peek()) {
        '−', '-' -> { i++; unary().negate() }
        '+' -> { i++; unary() }
        else -> postfix()
    }

    private fun postfix(): BigDecimal {
        var v = primary()
        lastWasPercent = peek() == '%'
        while (peek() == '%') {
            i++
            v = v.divide(HUNDRED, MC)
        }
        return v
    }

    private fun primary(): BigDecimal {
        if (peek() == '(') {
            i++
            val v = expr()
            if (peek() == ')') i++ else if (i != s.length) throw IllegalArgumentException("missing )")
            return v
        }
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        if (i < s.length && s[i] == 'E') {
            i++
            if (i < s.length && (s[i] == '−' || s[i] == '-')) i++
            while (i < s.length && s[i].isDigit()) i++
        }
        var tok = s.substring(start, i).replace('−', '-')
        if (tok.isEmpty() || tok == ".") throw IllegalArgumentException("number expected")
        if (tok.startsWith(".")) tok = "0$tok"
        tok = tok.replace(".E", ".0E")
        if (tok.endsWith(".")) tok += "0"
        return BigDecimal(tok)
    }
}
