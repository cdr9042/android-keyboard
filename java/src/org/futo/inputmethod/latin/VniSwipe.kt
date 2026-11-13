package org.futo.inputmethod.latin

import java.text.Normalizer
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Helpers that let swipe typing understand the VNI tone keys.
 *
 * The VNI layout types tones/diacritics with fullwidth digits U+FF11..U+FF19 (see VNICombiner).
 * The swipe model only knows letters, so tone-key points are removed from the path and the
 * tone keys the user crossed are used to re-rank the model's candidates instead.
 *
 * Kept free of Android types so it can be unit tested on the JVM.
 */
internal object VniSwipe {
    /** A tone key is picked when the path turns at least this much on it. */
    const val MIN_TURN_DEGREES = 60.0

    private const val FULLWIDTH_ZERO = 0xFF10

    fun isToneCode(code: Int): Boolean = code in (FULLWIDTH_ZERO + 1)..(FULLWIDTH_ZERO + 9)

    fun toneDigit(code: Int): Int = code - FULLWIDTH_ZERO

    /** The VNI digits needed to type the marks of [word], e.g. "việt" -> {5, 6}. */
    fun markDigits(word: String): Set<Int> {
        val digits = mutableSetOf<Int>()
        for (ch in Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)) {
            when (ch) {
                '́' -> digits.add(1) // acute
                '̀' -> digits.add(2) // grave
                '̉' -> digits.add(3) // hook above
                '̃' -> digits.add(4) // tilde
                '̣' -> digits.add(5) // dot below
                '̂' -> digits.add(6) // circumflex
                '̛' -> digits.add(7) // horn
                '̆' -> digits.add(8) // breve
                'đ' -> digits.add(9) // stroke
            }
        }
        return digits
    }

    /** For each of the first [length] points, the tone digit of the key it is on, or 0. */
    fun toneDigitsAt(xs: IntArray, ys: IntArray, length: Int, toneAt: (Int, Int) -> Int): IntArray =
        IntArray(length) { toneAt(xs[it], ys[it]) }

    /**
     * Tone digits the gesture deliberately picked: a tone key counts only where the path turns by
     * at least [MIN_TURN_DEGREES] on it, or where the gesture starts or ends on it. Keys merely
     * swept over on the way (6 to 1 passes 5, 4, 3 and 2) do not count.
     *
     * The path is first thinned to points at least [minStep] apart so jitter is not read as a
     * turn. [digits] holds the tone digit under each point (0 = not on a tone key).
     */
    fun pickedTones(xs: IntArray, ys: IntArray, digits: IntArray, minStep: Float): Set<Int> {
        val n = digits.size
        if (n == 0) return emptySet()

        val picked = mutableSetOf<Int>()
        if (digits[0] != 0) picked.add(digits[0])
        if (digits[n - 1] != 0) picked.add(digits[n - 1])

        val kept = mutableListOf(0)
        for (i in 1 until n) {
            val last = kept.last()
            if (hypot((xs[i] - xs[last]).toFloat(), (ys[i] - ys[last]).toFloat()) >= minStep) kept.add(i)
        }

        // Compare directions over one and two thinned steps, so a rounded corner still counts
        for (j in kept.indices) {
            val digit = digits[kept[j]]
            if (digit == 0) continue
            for (w in 1..2) {
                if (j - w < 0 || j + w >= kept.size) continue
                if (turnDegrees(xs, ys, kept[j - w], kept[j], kept[j + w]) >= MIN_TURN_DEGREES) {
                    picked.add(digit)
                    break
                }
            }
        }
        return picked
    }

    /** Angle between the direction a -> b and the direction b -> c, in degrees (0 = straight). */
    private fun turnDegrees(xs: IntArray, ys: IntArray, a: Int, b: Int, c: Int): Double {
        val ux = (xs[b] - xs[a]).toDouble()
        val uy = (ys[b] - ys[a]).toDouble()
        val vx = (xs[c] - xs[b]).toDouble()
        val vy = (ys[c] - ys[b]).toDouble()
        val lengths = hypot(ux, uy) * hypot(vx, vy)
        if (lengths == 0.0) return 0.0
        return Math.toDegrees(acos(((ux * vx + uy * vy) / lengths).coerceIn(-1.0, 1.0)))
    }

    /**
     * Orders candidates by how well their marks fit the tone keys that were crossed.
     *
     * A swipe along the tone row also crosses the keys between the ones the user meant (6 to 1
     * passes 5, 4, 3 and 2), so a candidate may use any subset of [crossed] rather than all of it:
     *  1. candidates whose marks are all crossed, using the most marks first,
     *  2. candidates without marks,
     *  3. candidates using a mark that was not crossed.
     * Fitting candidates using as many marks are ordered by [pathScoreOf], how well the swipe
     * matches their letters (higher is better). Otherwise each group keeps its relative order.
     * Returns [candidates] unchanged when nothing was crossed or no candidate with marks fits.
     */
    fun <T> rerank(
        candidates: List<T>,
        crossed: Set<Int>,
        pathScoreOf: (T) -> Float = { 0f },
        wordOf: (T) -> String
    ): List<T> {
        if (crossed.isEmpty()) return candidates

        val marks = candidates.map { markDigits(wordOf(it)) }
        val fitting = candidates.indices.filter { marks[it].isNotEmpty() && crossed.containsAll(marks[it]) }
        if (fitting.isEmpty()) return candidates

        val unmarked = candidates.indices.filter { marks[it].isEmpty() }
        val unfit = candidates.indices.filter { marks[it].isNotEmpty() && !crossed.containsAll(marks[it]) }

        val bestFirst = fitting.sortedWith(
            compareByDescending<Int> { marks[it].size }.thenByDescending { pathScoreOf(candidates[it]) }
        )
        return (bestFirst + unmarked + unfit).map { candidates[it] }
    }

    private const val VOWELS = "aeiouy"
    private val TONE_MARKS = mapOf(1 to '́', 2 to '̀', 3 to '̉', 4 to '̃', 5 to '̣')
    private val SHAPE_MARKS = mapOf(6 to ('̂' to "aeo"), 7 to ('̛' to "ou"), 8 to ('̆' to "a"))

    /** [word] without any marks, e.g. "Đường" -> "Duong". */
    fun baseLetters(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .replace('đ', 'd').replace('Đ', 'D')

    /**
     * Spellings of [word]'s base letters carrying exactly the marks in [tones], e.g. "men" or
     * "mèn" with {1, 6} -> mến, "thiết" with {5, 6} -> thiệt. The model often offers the right
     * letters with the wrong marks; the caller keeps the spellings the dictionary knows.
     */
    fun markedVariants(word: String, tones: Set<Int>): List<String> {
        if (tones.isEmpty()) return emptyList()
        val base = baseLetters(word)

        // Each letter's options as (spelling, uses a tone)
        val options = base.map { ch ->
            val lower = ch.lowercaseChar()
            val shaped = mutableListOf(ch.toString())
            for ((digit, mark) in SHAPE_MARKS) {
                if (digit in tones && lower in mark.second) shaped.add("$ch${mark.first}")
            }
            if (lower == 'd' && 9 in tones) shaped.add(if (ch.isUpperCase()) "Đ" else "đ")
            val toned = if (lower in VOWELS) {
                shaped.flatMap { s -> TONE_MARKS.filterKeys { it in tones }.values.map { "$s$it" } }
            } else emptyList()
            shaped.map { it to false } + toned.map { it to true }
        }

        val out = mutableListOf<String>()
        fun build(i: Int, prefix: String, toneUsed: Boolean) {
            if (out.size >= MAX_VARIANTS) return
            if (i == options.size) {
                val composed = Normalizer.normalize(prefix, Normalizer.Form.NFC)
                if (markDigits(composed) == tones) out.add(composed)
                return
            }
            for ((spelling, isTone) in options[i]) {
                if (isTone && toneUsed) continue
                build(i + 1, prefix + spelling, toneUsed || isTone)
            }
        }
        build(0, "", false)
        return out
    }

    private const val MAX_VARIANTS = 64
}
