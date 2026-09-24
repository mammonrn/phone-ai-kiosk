package com.mammonrn.phoneaikiosk.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor

/**
 * The calculator's arithmetic (0.52.0, Poom): what the keys type, evaluated.
 * Plain Kotlin, no Android — `CalculatorTest` runs it on the JVM.
 *
 * WHAT IT READS: numbers ("12", "0.5", "1.5E-7"), + - × ÷ (and * /), ^,
 * postfix ! and ², √ before a value, parentheses, the functions
 * sin cos tan asin acos atan ln log, and π, e, Ans. A number written next to
 * "(", a function or a constant multiplies it ("2π", "3(4)"). Parentheses
 * left open at the end are closed, as a pocket calculator does.
 *
 * NEVER A WRONG ANSWER SILENTLY. Division by zero, a value outside a
 * function's domain (√ of a negative, log of 0, tan 90°, asin 2), a factorial
 * of anything but a whole number 0-170, and a result too big for a double are
 * [Result.Error]s with a reason, not NaN or Infinity on the screen. In degrees,
 * the angles where sin/cos/tan are exact (multiples of 30° and 45°) come out
 * exact: sin 180° is 0, not 1.2E-16.
 */
object CalcEngine {

    sealed class Result {
        data class Value(val value: Double) : Result()
        data class Error(val reason: Reason) : Result()
    }

    enum class Reason { DIVIDE_BY_ZERO, DOMAIN, TOO_BIG, INCOMPLETE }

    private class Fail(val reason: Reason) : RuntimeException(null, null, false, false)

    /** Evaluates [input]. [degrees]: the trig functions take and give degrees. */
    fun evaluate(input: String, degrees: Boolean = true, ans: Double = 0.0): Result = try {
        val parser = Parser(tokenize(input), degrees, ans)
        val value = parser.parse()
        when {
            value.isNaN() -> Result.Error(Reason.DOMAIN)
            value.isInfinite() -> Result.Error(Reason.TOO_BIG)
            else -> Result.Value(if (value == 0.0) 0.0 else value)   // no "-0"
        }
    } catch (fail: Fail) {
        Result.Error(fail.reason)
    }

    // ------------------------------------------------------------- tokens

    private sealed class Tok {
        data class Num(val v: Double) : Tok()
        data class Op(val c: Char) : Tok()          // + - × ÷ ^ ! ² √ ( )
        data class Fn(val name: String) : Tok()
        data class Const(val v: Double) : Tok()
    }

    private val FUNCTIONS = listOf("asin", "acos", "atan", "sin", "cos", "tan", "ln", "log")

    private fun tokenize(input: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val s = input.replace('−', '-').replace('*', '×').replace('/', '÷').replace(" ", "")
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() || c == '.' -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                    // An exponent: "E", then an optional sign, then digits.
                    if (j < s.length && s[j] == 'E') {
                        var k = j + 1
                        if (k < s.length && (s[k] == '-' || s[k] == '+')) k++
                        if (k < s.length && s[k].isDigit()) {
                            while (k < s.length && s[k].isDigit()) k++
                            j = k
                        } else throw Fail(Reason.INCOMPLETE)          // "2E" or "2E-"
                    }
                    val text = s.substring(i, j)
                    if (text.count { it == '.' } > 1 || text.startsWith(".E") || text == ".")
                        throw Fail(Reason.INCOMPLETE)
                    out.add(Tok.Num(text.toDouble()))
                    i = j
                }
                c in "+-×÷^!²√()" -> { out.add(Tok.Op(c)); i++ }
                c == 'π' -> { out.add(Tok.Const(PI)); i++ }
                s.startsWith("Ans", i) -> { out.add(Tok.Const(Double.NaN)); i += 3 }   // replaced by the parser
                c == 'e' -> { out.add(Tok.Const(kotlin.math.E)); i++ }
                else -> {
                    val name = FUNCTIONS.firstOrNull { s.startsWith(it, i) } ?: throw Fail(Reason.INCOMPLETE)
                    out.add(Tok.Fn(name)); i += name.length
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------- grammar

    private class Parser(val toks: List<Tok>, val degrees: Boolean, val ans: Double) {
        var pos = 0

        fun parse(): Double {
            if (toks.isEmpty()) throw Fail(Reason.INCOMPLETE)
            val v = expr()
            // Anything left over but closing parentheses is a mistake.
            while (pos < toks.size && toks[pos] == Tok.Op(')')) pos++
            if (pos < toks.size) throw Fail(Reason.INCOMPLETE)
            return v
        }

        private fun peek(): Tok? = toks.getOrNull(pos)

        private fun expr(): Double {
            var v = term()
            while (true) {
                v = when (peek()) {
                    Tok.Op('+') -> { pos++; check(v + term()) }
                    Tok.Op('-') -> { pos++; check(v - term()) }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                val t = peek()
                v = when {
                    t == Tok.Op('×') -> { pos++; check(v * unary()) }
                    t == Tok.Op('÷') -> {
                        pos++
                        val d = unary()
                        if (d == 0.0) throw Fail(Reason.DIVIDE_BY_ZERO)
                        check(v / d)
                    }
                    // "2π", "3(4)", "2sin30", "2√9": a value next to a value.
                    t is Tok.Const || t is Tok.Fn || t == Tok.Op('(') || t == Tok.Op('√') -> check(v * unary())
                    else -> return v
                }
            }
        }

        private fun unary(): Double = when (peek()) {
            Tok.Op('-') -> { pos++; -unary() }
            Tok.Op('+') -> { pos++; unary() }
            else -> power()
        }

        /** Right-associative: 2^3^2 = 2^9. -2^2 = -4, as on paper. */
        private fun power(): Double {
            val base = postfix()
            if (peek() == Tok.Op('^')) {
                pos++
                val exp = unary()
                if (base == 0.0 && exp < 0) throw Fail(Reason.DIVIDE_BY_ZERO)
                if (base < 0 && exp != floor(exp)) throw Fail(Reason.DOMAIN)
                return check(Math.pow(base, exp))
            }
            return base
        }

        private fun postfix(): Double {
            var v = primary()
            while (true) {
                v = when (peek()) {
                    Tok.Op('!') -> { pos++; factorial(v) }
                    Tok.Op('²') -> { pos++; check(v * v) }
                    else -> return v
                }
            }
        }

        private fun primary(): Double {
            val t = peek() ?: throw Fail(Reason.INCOMPLETE)
            pos++
            return when (t) {
                is Tok.Num -> t.v
                is Tok.Const -> if (t.v.isNaN()) ans else t.v
                Tok.Op('(') -> group()
                Tok.Op('√') -> {
                    val v = if (peek() == Tok.Op('-')) unary() else postfix()
                    if (v < 0) throw Fail(Reason.DOMAIN)
                    Math.sqrt(v)
                }
                is Tok.Fn -> apply(t.name, argument())
                else -> throw Fail(Reason.INCOMPLETE)
            }
        }

        /** After "(": the inside, and ")" if it is there (open ones close at the end). */
        private fun group(): Double {
            val v = expr()
            if (peek() == Tok.Op(')')) pos++
            else if (pos < toks.size) throw Fail(Reason.INCOMPLETE)
            return v
        }

        /** A function's argument: "(…)", or a bare value ("sin30"). */
        private fun argument(): Double =
            if (peek() == Tok.Op('(')) { pos++; group() } else unary()

        private fun apply(name: String, x: Double): Double = when (name) {
            "sin" -> if (degrees) sinDeg(x) else Math.sin(x)
            "cos" -> if (degrees) cosDeg(x) else Math.cos(x)
            "tan" -> if (degrees) tanDeg(x) else Math.tan(x)
            "asin" -> { if (x < -1 || x > 1) throw Fail(Reason.DOMAIN); angleOut(Math.asin(x)) }
            "acos" -> { if (x < -1 || x > 1) throw Fail(Reason.DOMAIN); angleOut(Math.acos(x)) }
            "atan" -> angleOut(Math.atan(x))
            "ln" -> { if (x <= 0) throw Fail(Reason.DOMAIN); Math.log(x) }
            "log" -> { if (x <= 0) throw Fail(Reason.DOMAIN); Math.log10(x) }
            else -> throw Fail(Reason.INCOMPLETE)
        }

        private fun angleOut(radians: Double) = if (degrees) snap(Math.toDegrees(radians)) else radians

        private fun check(v: Double): Double {
            if (v.isInfinite()) throw Fail(Reason.TOO_BIG)
            if (v.isNaN()) throw Fail(Reason.DOMAIN)
            return v
        }
    }

    private fun factorial(x: Double): Double {
        if (x < 0 || x != floor(x) || x > 170) throw Fail(if (x > 170) Reason.TOO_BIG else Reason.DOMAIN)
        var out = 1.0
        for (k in 2..x.toInt()) out *= k
        return out
    }

    // ------------------------------------------------------------- exact angles

    /** Angles within a hair of a whole degree are that degree (asin 0.5 = 30). */
    private fun snap(deg: Double): Double {
        val r = Math.rint(deg)
        return if (abs(deg - r) < 1e-9) r else deg
    }

    private fun norm(deg: Double): Double { val r = deg % 360.0; return if (r < 0) r + 360.0 else r }

    /** sin of whole-degree multiples of 30 and 45, exactly; the rest by Math.sin. */
    internal fun sinDeg(x: Double): Double {
        val d = norm(x)
        when (d) {
            0.0, 180.0 -> return 0.0
            90.0 -> return 1.0
            270.0 -> return -1.0
            30.0, 150.0 -> return 0.5
            210.0, 330.0 -> return -0.5
        }
        return Math.sin(Math.toRadians(x))
    }

    internal fun cosDeg(x: Double): Double = sinDeg(x + 90.0)

    internal fun tanDeg(x: Double): Double {
        val d = norm(x) % 180.0
        return when (d) {
            0.0 -> 0.0
            90.0 -> throw Fail(Reason.DOMAIN)
            45.0 -> 1.0
            135.0 -> -1.0
            else -> Math.tan(Math.toRadians(x))
        }
    }

    // ------------------------------------------------------------- the display

    /** Significant digits shown: 10, as a 1995 scientific calculator. */
    const val DIGITS = 10

    /**
     * "60", "0.3333333333", "1.5E-7", "6.02214076E23": plain between 1E-6 and
     * 1E10, powers of ten outside, never more than 10 significant digits and
     * never trailing zeros. At most 16 characters, so it fits the display.
     */
    fun format(value: Double): String {
        if (value == 0.0) return "0"
        // A long exponent ("-1.23E-100") takes a digit or two off the mantissa.
        for (digits in DIGITS downTo 4) {
            val text = format(value, digits)
            if (text.length <= MAX_CHARS) return text
        }
        return format(value, 4)
    }

    /** The widest result the display takes at its size. */
    const val MAX_CHARS = 16

    private fun format(value: Double, digits: Int): String {
        val rounded = BigDecimal(value).round(MathContext(digits, RoundingMode.HALF_EVEN))
        val a = rounded.abs()
        return if (a >= BigDecimal("1E10") || a < BigDecimal("1E-6")) {
            val exp = rounded.precision() - rounded.scale() - 1
            val mantissa = rounded.movePointLeft(exp).stripTrailingZeros().toPlainString()
            "${mantissa}E$exp"
        } else {
            rounded.stripTrailingZeros().toPlainString()
        }
    }
}
