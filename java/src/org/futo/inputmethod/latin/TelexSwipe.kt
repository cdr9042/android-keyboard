package org.futo.inputmethod.latin

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Helpers that let swipe typing understand Telex marks on a plain QWERTY layout.
 *
 * Telex types marks with letters: s f r x j for the tones, aa ee oo for the circumflex, w for the
 * horn or breve and dd for the stroke. In a swipe, a mark letter is a key the finger clearly meant
 * (see [VniSwipe.meantPoints]) after a vowel, and a doubled letter is a loop drawn on the key. The
 * marks are turned into VNI digits so [VniSwipe] can spell and re-rank the candidates, and the trips
 * to the mark keys are cut from the path so the letters-only model does not see them.
 *
 * Kept free of Android types so it can be unit tested on the JVM.
 */
internal object TelexSwipe {
    /** A loop turns the path by at least this much in one direction. */
    const val LOOP_DEGREES = 270.0

    /** A loop stays within this many key widths of where it started. */
    private const val LOOP_KEY_WIDTHS = 1.2f

    /** A loop is round: a step turning more than this is a corner, not part of a loop. */
    private const val MAX_LOOP_STEP_DEGREES = 120.0

    /** A loop starts turning right away; a straight run before it is not part of it. */
    private const val MIN_LOOP_STEP_DEGREES = 10.0

    private const val VOWELS = "aeiouy"

    /** Letters a Vietnamese word can end on: a key near one of them after a vowel is no mark. */
    private const val FINALS = "cghmnpt"

    private val TONES = mapOf('s' to 1, 'f' to 2, 'r' to 3, 'x' to 4, 'j' to 5)

    /**
     * [picked]: the marks the gesture typed, as VNI digits. [keep]: per point, whether it goes to the
     * letter model. [anchors]: the letters the gesture surely typed, marks left out.
     */
    class Split(val picked: Set<Int>, val keep: BooleanArray, val anchors: List<VniSwipe.Anchor>)

    /** A key the gesture meant, over the points [from]..[to]. */
    private class Event(
        val from: Int, val to: Int, val letters: Set<Char>,
        val isLoop: Boolean, val isStart: Boolean, val isEnd: Boolean
    )

    /**
     * Splits a gesture into the marks it typed and the points the letter model should see.
     *
     * A meant key after a vowel is a mark when it is s f r x j (tones) or w (horn after o or u,
     * breve after a), and no vowel or final letter is as near. A loop on a, e or o is the
     * circumflex, on d before any vowel the stroke. Each trip to a mark key is cut from the letter
     * meant before it to the one after it, and each loop reaches the model as its first point.
     */
    fun split(xs: IntArray, ys: IntArray, ts: IntArray, n: Int, keys: List<VniSwipe.KeyBox>, keyWidth: Float): Split {
        val keep = BooleanArray(n) { true }
        if (n == 0) return Split(emptySet(), keep, emptyList())

        val loops = loops(xs, ys, n, keyWidth)
        for (loop in loops) for (i in loop.first + 1..loop.last) keep[i] = false

        // Meant points inside a loop belong to it; the loop is read at its centre
        val raw = mutableListOf<Triple<IntRange, Int, Int>>()
        // Separate corners: going up to w and on to n are two turns close together (quawng)
        for (i in VniSwipe.meantPoints(xs, ys, ts, n, keyWidth, separateCorners = true)) {
            if (loops.none { i in it }) raw.add(Triple(i..i, xs[i], ys[i]))
        }
        for (loop in loops) {
            raw.add(Triple(loop, loop.map { xs[it] }.average().toInt(), loop.map { ys[it] }.average().toInt()))
        }
        raw.sortBy { it.first.first }

        // Neighbouring events that may be the same key are one, as in VniSwipe.anchors
        val events = mutableListOf<Event>()
        var canMerge = false
        for ((range, x, y) in raw) {
            val letters = VniSwipe.nearLetters(x, y, keys)
            if (letters == null) { canMerge = false; continue }
            val e = Event(range.first, range.last, letters, range in loops, range.first == 0, range.last == n - 1)
            val prev = events.lastOrNull()
            val both = if (canMerge && prev != null) prev.letters intersect letters else emptySet()
            if (prev != null && both.isNotEmpty()) {
                events[events.lastIndex] = Event(
                    prev.from, maxOf(prev.to, e.to), both,
                    prev.isLoop || e.isLoop, prev.isStart || e.isStart, prev.isEnd || e.isEnd
                )
            } else {
                events.add(e)
            }
            canMerge = true
        }

        // The vowel each point is clearly on, if any: a fast swipe may run straight through the vowels
        // (h, u, o on the way to w in chương), so vowels passed over count too
        val vowelAt = Array(n) { i ->
            VniSwipe.nearLetters(xs[i], ys[i], keys)?.takeIf { l -> l.all { it in VOWELS } }
        }

        val picked = mutableSetOf<Int>()
        val isMark = BooleanArray(events.size)
        var lastVowel: Set<Char>? = null
        var lastVowelAt = -1
        for ((k, e) in events.withIndex()) {
            (e.from - 1 downTo lastVowelAt + 1).firstOrNull { vowelAt[it] != null }?.let {
                lastVowel = vowelAt[it]
                lastVowelAt = it
            }
            val mark = lastVowel?.let { markOf(e.letters, it) }
            if (mark != null) {
                picked.add(mark)
                isMark[k] = true
                continue
            }
            if (e.isLoop) {
                if (e.letters.any { it in "aeo" }) picked.add(6)
                else if ('d' in e.letters && lastVowel == null) picked.add(9)
            }
            if (e.letters.any { it in VOWELS }) {
                lastVowel = e.letters
                lastVowelAt = e.to
            }
        }

        for (k in events.indices) {
            if (!isMark[k]) continue
            val before = (k - 1 downTo 0).firstOrNull { !isMark[it] }?.let { events[it] }
            val to = (k + 1 until events.size).firstOrNull { !isMark[it] }?.let { events[it].from } ?: n
            val from = if (before == null) -1 else endingLetters(xs, ys, before, events[k].from, keys)
            for (i in from + 1 until to) keep[i] = false
        }

        val anchors = events.filterIndexed { k, _ -> !isMark[k] }
            .map { VniSwipe.Anchor(it.letters, it.isStart, it.isEnd) }
        return Split(picked, keep, anchors)
    }

    /**
     * Where the letters end on the way from the meant letter [before] to a mark key, which the
     * path reaches at [until]. A swept-over key whose letter may come next in a word ending is
     * kept (n then g in chuongws, the path n-g-w being nearly straight): the last point nearest
     * its centre is returned. Keys that may not come next are swept over on the way (g between
     * h and f in thanhf).
     */
    private fun endingLetters(xs: IntArray, ys: IntArray, before: Event, until: Int, keys: List<VniSwipe.KeyBox>): Int {
        var end = before.to
        var current = before.letters
        var bestDist = Float.MAX_VALUE
        for (i in before.to + 1 until until) {
            val letter = VniSwipe.nearLetters(xs[i], ys[i], keys)?.singleOrNull() ?: continue
            if (letter in current) {
                // Still on the kept key: move to the point nearest its centre
                val box = keys.first { it.letter == letter }
                val d = hypot(xs[i] - (box.left + box.right) / 2f, ys[i] - (box.top + box.bottom) / 2f)
                if (end != before.to && d < bestDist) { end = i; bestDist = d }
                continue
            }
            if (current.none { letter in (FOLLOWING[it] ?: "") }) continue
            val box = keys.first { it.letter == letter }
            bestDist = hypot(xs[i] - (box.left + box.right) / 2f, ys[i] - (box.top + box.bottom) / 2f)
            end = i
            current = setOf(letter)
        }
        return end
    }

    /** The letters that may come next in a word ending, after each letter. */
    private val FOLLOWING = VOWELS.associateWith { "cmnpt" } + mapOf('n' to "gh", 'c' to "h")

    /**
     * The VNI digit of the mark typed by a meant key on [letters] after the vowel [lastVowel], or
     * null when it is a letter: a vowel or a letter that can end a word is as near (g next to f).
     */
    fun markOf(letters: Set<Char>, lastVowel: Set<Char>): Int? {
        if (letters.any { it in VOWELS || it in FINALS }) return null
        letters.firstNotNullOfOrNull { TONES[it] }?.let { return it }
        if ('w' in letters) {
            return if ('a' in lastVowel && 'o' !in lastVowel && 'u' !in lastVowel) 8 else 7
        }
        return null
    }

    /**
     * The loops of the path, as point ranges: runs that keep turning one way by at least
     * [LOOP_DEGREES] without a sharp corner, staying within [LOOP_KEY_WIDTHS] of where they start.
     * A back-and-forth turns both ways, so it is no loop.
     */
    fun loops(xs: IntArray, ys: IntArray, n: Int, keyWidth: Float): List<IntRange> {
        // Thinned so jitter does not count as turning
        val kept = mutableListOf<Int>()
        for (i in 0 until n) {
            val last = kept.lastOrNull()
            if (last == null || hypot((xs[i] - xs[last]).toFloat(), (ys[i] - ys[last]).toFloat()) >= keyWidth / 8f) kept.add(i)
        }
        if (kept.size < 4) return emptyList()

        val heading = DoubleArray(kept.size - 1) {
            atan2((ys[kept[it + 1]] - ys[kept[it]]).toDouble(), (xs[kept[it + 1]] - xs[kept[it]]).toDouble())
        }
        // Signed turn at kept[j], between the steps before and after it
        fun turn(j: Int): Double {
            var d = Math.toDegrees(heading[j] - heading[j - 1])
            while (d > 180) d -= 360
            while (d <= -180) d += 360
            return d
        }

        val out = mutableListOf<IntRange>()
        var j = 1
        while (j < kept.size - 1) {
            if (abs(turn(j)) < MIN_LOOP_STEP_DEGREES) { j++; continue }
            val start = kept[j - 1]
            var sum = 0.0
            var end = -1
            for (k in j until kept.size - 1) {
                val t = turn(k)
                val far = hypot((xs[kept[k + 1]] - xs[start]).toFloat(), (ys[kept[k + 1]] - ys[start]).toFloat())
                if (abs(t) > MAX_LOOP_STEP_DEGREES || far > keyWidth * LOOP_KEY_WIDTHS) break
                sum += t
                if (abs(sum) >= LOOP_DEGREES) { end = k; break }
            }
            if (end < 0) { j++; continue }
            out.add(start..kept[end + 1])
            j = end + 1
        }
        return out
    }
}
