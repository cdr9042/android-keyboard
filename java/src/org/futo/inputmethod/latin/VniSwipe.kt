package org.futo.inputmethod.latin

import java.text.Normalizer
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Helpers that let swipe typing understand the VNI tone keys.
 *
 * The VNI layout types tones/diacritics with fullwidth digits U+FF11..U+FF19 (see VNICombiner).
 * The swipe model only knows letters, so the trips to the tone row are cut from the path and the
 * tone keys the user picked are used to re-rank the model's candidates instead.
 *
 * Kept free of Android types so it can be unit tested on the JVM.
 */
internal object VniSwipe {
    /**
     * A point is slow, i.e. the finger rests on a key it means, below this speed. Measured on a
     * device: meant keys 0.0-0.33 px/ms, keys merely swept over 0.95-3.9 px/ms, keys 107 px wide.
     */
    const val SLOW_KEY_WIDTHS_PER_MS = 0.005f

    /** Speed is measured over the points this close in time, on both sides. */
    private const val SPEED_WINDOW_MS = 30

    /**
     * The finger rests on a key only when it stays slow this long. Measured: meant keys stay slow
     * 90 ms or more, a turn that merely slows down for a moment about 35 ms.
     */
    const val REST_MS = 60

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
     * [picked]: tone digits the gesture meant. [keep]: per point, whether it goes to the letter
     * model.
     */
    class ToneSplit(val picked: Set<Int>, val keep: BooleanArray)

    /**
     * Splits a gesture into the tone keys it meant and the points the letter model should see.
     *
     * A tone key counts only where the finger rests on it (stays slow for [REST_MS]), or where
     * the gesture starts or ends on it. Keys merely swept over on the way (6 to 1 passes 5, 4, 3
     * and 2) are fast.
     *
     * Each trip to the tone row is cut from the last letter the finger rested on before it to the
     * first one after it, so letters swept over on the way up and back down, or between two tone
     * keys, do not reach the model. With no such letter on a side, only the tone-row points are
     * cut on that side.
     *
     * [digits] holds the tone digit under each point (0 = not on a tone key), [ts] the time in ms.
     */
    fun splitTones(xs: IntArray, ys: IntArray, ts: IntArray, digits: IntArray, keyWidth: Float): ToneSplit {
        val n = digits.size
        val keep = BooleanArray(n) { digits[it] == 0 }
        if (n == 0) return ToneSplit(emptySet(), keep)

        val rest = restingPoints(xs, ys, ts, n, keyWidth * SLOW_KEY_WIDTHS_PER_MS)

        val picked = mutableSetOf<Int>()
        if (digits[0] != 0) picked.add(digits[0])
        if (digits[n - 1] != 0) picked.add(digits[n - 1])
        for (i in 0 until n) if (rest[i] && digits[i] != 0) picked.add(digits[i])

        val restsOnLetter = { i: Int -> rest[i] && digits[i] == 0 }
        var start = 0
        while (start < n) {
            if (digits[start] == 0) { start++; continue }
            var end = start
            while (end + 1 < n && digits[end + 1] != 0) end++
            val from = (start - 1 downTo 0).firstOrNull(restsOnLetter) ?: (start - 1)
            val to = (end + 1 until n).firstOrNull(restsOnLetter) ?: (end + 1)
            for (i in from + 1 until to) keep[i] = false
            start = end + 1
        }
        return ToneSplit(picked, keep)
    }

    /** Whether the finger rests at each point: slower than [maxSpeed] px/ms for [REST_MS]. */
    private fun restingPoints(xs: IntArray, ys: IntArray, ts: IntArray, n: Int, maxSpeed: Float): BooleanArray {
        val slow = BooleanArray(n)
        var a = 0
        var b = 0
        for (i in 0 until n) {
            while (ts[i] - ts[a] > SPEED_WINDOW_MS) a++
            if (b < i) b = i
            while (b + 1 < n && ts[b + 1] - ts[i] <= SPEED_WINDOW_MS) b++
            // Always reach one neighbour on each side: a resting finger may send no events for a while
            val lo = minOf(a, maxOf(i - 1, 0))
            val hi = maxOf(b, minOf(i + 1, n - 1))
            val dist = hypot((xs[hi] - xs[lo]).toFloat(), (ys[hi] - ys[lo]).toFloat())
            val dt = ts[hi] - ts[lo]
            slow[i] = if (dt > 0) dist / dt < maxSpeed else dist == 0f
        }

        val rest = BooleanArray(n)
        var first = 0
        while (first < n) {
            if (!slow[first]) { first++; continue }
            var last = first
            while (last + 1 < n && slow[last + 1]) last++
            if (ts[last] - ts[first] >= REST_MS) for (i in first..last) rest[i] = true
            first = last + 1
        }
        return rest
    }

    /** A key's hit box; [letter] is null for keys that are not letters (tone keys, digits, shift). */
    class KeyBox(val letter: Char?, val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * A letter the gesture surely typed: where it starts, ends, rests ([REST_MS]) or turns sharply
     * ([MIN_TURN_DEGREES]). [letters] holds every key the point may be on (it can sit on a border).
     */
    data class Anchor(val letters: Set<Char>, val isStart: Boolean = false, val isEnd: Boolean = false)

    /** A sharp turn of the path at a letter is taken as meant. */
    const val MIN_TURN_DEGREES = 60.0

    /** Keys at most this much farther away (in key sizes) than the nearest one are also candidates. */
    const val NEAR_MARGIN = 0.35f

    /**
     * The letters the point at ([x], [y]) may be meant for: the nearest key and any within
     * [NEAR_MARGIN] of it. Null when one of them is not a letter: near the tone row the tone logic
     * decides, near other keys nothing is sure.
     */
    fun nearLetters(x: Int, y: Int, keys: List<KeyBox>): Set<Char>? {
        val dist = keys.map { k ->
            val w = (k.right - k.left).toFloat()
            val h = (k.bottom - k.top).toFloat()
            hypot((x - (k.left + k.right) / 2f) / w, (y - (k.top + k.bottom) / 2f) / h)
        }
        val best = dist.minOrNull() ?: return null
        val near = keys.filterIndexed { i, _ -> dist[i] <= best + NEAR_MARGIN }
        if (near.any { it.letter == null }) return null
        return near.map { it.letter!!.lowercaseChar() }.toSet()
    }

    /**
     * The letters the gesture surely typed, in order (see [Anchor]). Keys merely swept over are
     * left out. Neighbouring anchors that may be the same key are merged.
     */
    fun anchors(xs: IntArray, ys: IntArray, ts: IntArray, n: Int, keys: List<KeyBox>, keyWidth: Float): List<Anchor> {
        if (n == 0) return emptyList()
        val events = sortedSetOf(0, n - 1)

        val rest = restingPoints(xs, ys, ts, n, keyWidth * SLOW_KEY_WIDTHS_PER_MS)
        var first = 0
        while (first < n) {
            if (!rest[first]) { first++; continue }
            var last = first
            while (last + 1 < n && rest[last + 1]) last++
            events.add((first + last) / 2)
            first = last + 1
        }
        events.addAll(turnPoints(xs, ys, n, keyWidth / 3f))

        val out = mutableListOf<Anchor>()
        var canMerge = false
        for (e in events) {
            val letters = nearLetters(xs[e], ys[e], keys)
            if (letters == null) { canMerge = false; continue }
            val start = e == 0
            val end = e == n - 1
            val prev = out.lastOrNull()
            val both = if (canMerge && prev != null) prev.letters intersect letters else emptySet()
            if (prev != null && both.isNotEmpty()) {
                out[out.lastIndex] = Anchor(both, prev.isStart || start, prev.isEnd || end)
            } else {
                out.add(Anchor(letters, start, end))
            }
            canMerge = true
        }
        return out
    }

    /**
     * Points where the path turns by at least [MIN_TURN_DEGREES], one per turn (the sharpest).
     * The path is first thinned to points at least [minStep] apart so jitter is not read as a turn.
     */
    private fun turnPoints(xs: IntArray, ys: IntArray, n: Int, minStep: Float): List<Int> {
        val kept = mutableListOf(0)
        for (i in 1 until n) {
            val last = kept.last()
            if (hypot((xs[i] - xs[last]).toFloat(), (ys[i] - ys[last]).toFloat()) >= minStep) kept.add(i)
        }
        // Compare directions over one and two thinned steps, so a rounded corner still counts
        val angle = DoubleArray(kept.size) { j ->
            (1..2).filter { w -> j - w >= 0 && j + w < kept.size }
                .maxOfOrNull { w -> turnDegrees(xs, ys, kept[j - w], kept[j], kept[j + w]) } ?: 0.0
        }
        val out = mutableListOf<Int>()
        var j = 0
        while (j < kept.size) {
            if (angle[j] < MIN_TURN_DEGREES) { j++; continue }
            var end = j
            while (end + 1 < kept.size && angle[end + 1] >= MIN_TURN_DEGREES) end++
            out.add(kept[(j..end).maxByOrNull { angle[it] }!!])
            j = end + 1
        }
        return out
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
     * Whether [word]'s base letters hold every anchor, in order: a start anchor on the first
     * letter, an end anchor on the last one, the others in between.
     */
    fun fitsAnchors(word: String, anchors: List<Anchor>): Boolean {
        if (anchors.isEmpty()) return true
        val w = baseLetters(word).lowercase()
        if (w.isEmpty()) return false
        var next = 0
        for (a in anchors) {
            val at = when {
                a.isStart -> 0
                a.isEnd -> w.lastIndex
                else -> (next until w.length).firstOrNull { w[it] in a.letters } ?: return false
            }
            if (at < next || w[at] !in a.letters) return false
            if (a.isStart && a.isEnd && w.last() !in a.letters) return false
            next = if (a.isEnd) w.length else at + 1
        }
        return true
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
