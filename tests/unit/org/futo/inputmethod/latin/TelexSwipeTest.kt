package org.futo.inputmethod.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TelexSwipeTest {
    // Fake QWERTY keyboard, every key 100 x 100, rows staggered like the real one
    private val rows = listOf("qwertyuiop" to 0, "asdfghjkl" to 50, "zxcvbnm" to 150)
    private val keyBoxes = rows.flatMapIndexed { r, (letters, offset) ->
        letters.mapIndexed { i, c ->
            VniSwipe.KeyBox(c, offset + i * 100, r * 100, offset + i * 100 + 100, r * 100 + 100)
        }
    }

    /** A key the gesture goes to; it rests [pauseMs] there, or draws a loop on it. */
    private data class Corner(val key: Char, val pauseMs: Int = 0, val loop: Boolean = false)

    private class Gesture(val xs: IntArray, val ys: IntArray, val ts: IntArray)

    /** Straight lines between the keys' centres at a fast swipe speed (2 px/ms), points every 5 ms. */
    private fun gesture(vararg corners: Corner): Gesture {
        val xs = mutableListOf<Int>()
        val ys = mutableListOf<Int>()
        val ts = mutableListOf<Int>()
        var t = 0
        fun add(x: Int, y: Int) { xs.add(x); ys.add(y); ts.add(t) }
        fun centre(c: Char): Pair<Int, Int> = keyBoxes.first { it.letter == c }.let {
            (it.left + it.right) / 2 to (it.top + it.bottom) / 2
        }
        fun visit(c: Corner) {
            val (x, y) = centre(c.key)
            for (k in 1..c.pauseMs / 10) { t += 10; add(x, y) }
            if (c.loop) {
                // A circle of radius 25 above the centre, starting and ending on it
                for (k in 1..24) {
                    t += 5
                    val a = 2 * Math.PI * k / 24
                    add(x + (25 * sin(a)).toInt(), y - 25 + (25 * cos(a)).toInt())
                }
            }
        }
        val (x0, y0) = centre(corners[0].key)
        add(x0, y0)
        visit(corners[0])
        for (k in 1 until corners.size) {
            val (ax, ay) = xs.last() to ys.last()
            val (bx, by) = centre(corners[k].key)
            val steps = maxOf(abs(bx - ax), abs(by - ay)) / 10
            for (s in 1..steps) {
                t += 5
                add(ax + (bx - ax) * s / steps, ay + (by - ay) * s / steps)
            }
            visit(corners[k])
        }
        return Gesture(xs.toIntArray(), ys.toIntArray(), ts.toIntArray())
    }

    private fun corners(spec: String): Array<Corner> =
        // "a*" draws a loop on a
        Regex("[a-z]\\*?").findAll(spec).map { Corner(it.value[0], loop = it.value.endsWith("*")) }.toList().toTypedArray()

    private fun split(spec: String): Pair<Gesture, TelexSwipe.Split> {
        val g = gesture(*corners(spec))
        return g to TelexSwipe.split(g.xs, g.ys, g.ts, g.xs.size, keyBoxes, keyWidth = 100f)
    }

    private fun letters(s: TelexSwipe.Split): String =
        s.anchors.joinToString("") { a -> if (a.letters.size == 1) "${a.letters.first()}" else a.letters.sorted().joinToString("", "[", "]") }

    /** The keys the kept points pass through clearly (centre within 30 px), in order, deduplicated. */
    private fun keptKeys(g: Gesture, s: TelexSwipe.Split): String {
        val out = StringBuilder()
        for (i in g.xs.indices) {
            if (!s.keep[i]) continue
            val key = keyBoxes.firstOrNull {
                abs((it.left + it.right) / 2 - g.xs[i]) < 30 && abs((it.top + it.bottom) / 2 - g.ys[i]) < 30
            }?.letter ?: continue
            if (out.lastOrNull() != key) out.append(key)
        }
        return out.toString()
    }

    @Test
    fun aLoopIsFoundButNotAStraightLineOrAReversal() {
        val loop = gesture(*corners("ha*n"))
        assertEquals(1, TelexSwipe.loops(loop.xs, loop.ys, loop.xs.size, 100f).size)
        val straight = gesture(*corners("ql"))
        assertEquals(0, TelexSwipe.loops(straight.xs, straight.ys, straight.xs.size, 100f).size)
        val zigzag = gesture(*corners("uwow"))
        assertEquals(0, TelexSwipe.loops(zigzag.xs, zigzag.ys, zigzag.xs.size, 100f).size)
    }

    @Test
    fun thanWithALoopOnAAndJIsThan5And6() {
        // t-h-a-a-j-n: circumflex from the loop, dot below from j
        val (g, s) = split("tha*jn")
        assertEquals(setOf(5, 6), s.picked)
        assertEquals("than", letters(s))
        assertFalse("the trip to j is cut", keptKeys(g, s).contains('j'))
        assertTrue(s.keep.first() && s.keep.last())
    }

    @Test
    fun aMarkAtTheEndIsCutToo() {
        // thanj
        val (g, s) = split("thanj")
        assertEquals(setOf(5), s.picked)
        assertEquals("than", letters(s))
        assertFalse(s.keep.last())
        assertTrue(keptKeys(g, s).endsWith("n"))
    }

    @Test
    fun sRAndXBeforeTheVowelAreLetters() {
        assertEquals(setOf<Int>(), split("sang").second.picked)
        assertEquals("sang", letters(split("sang").second))
        assertEquals(setOf<Int>(), split("xin").second.picked)
        assertEquals(setOf<Int>(), split("ra").second.picked)
    }

    @Test
    fun noMarksWithoutMarkKeysOrLoops() {
        val (_, s) = split("than")
        assertEquals(setOf<Int>(), s.picked)
        assertTrue(s.keep.all { it })
    }

    @Test
    fun wAfterUOrOIsTheHornAfterAIsTheBreve() {
        assertEquals(setOf(8), split("awn").second.picked) // ăn
        assertEquals(setOf(7), split("thuw").second.picked) // thư
        assertEquals(setOf(7), split("muwa").second.picked) // mưa: u is only swept through
        assertEquals(setOf(8), split("quawng").second.picked) // quăng: the vowel right before w is a
    }

    @Test
    fun chuongWithAWAfterEachVowelAndS() {
        // c-h-u-w-o-w-n-g-s: the sweeps between u, w and o cross e, r and t without stopping
        val (g, s) = split("chuwowngs")
        assertEquals(setOf(1, 7), s.picked)
        // h and u are swept through on the diagonal, so only the corners are sure letters
        assertFalse(letters(s).contains('w') || letters(s).contains('s'))
        val kept = keptKeys(g, s)
        assertFalse("no trip to w is kept: $kept", kept.contains('w'))
        assertFalse(kept.endsWith("s"))
    }

    @Test
    fun chuongWithOneW() {
        val (g, s) = split("chuongws")
        assertEquals(setOf(1, 7), s.picked)
        assertFalse(keptKeys(g, s).contains('w'))
        assertTrue(keptKeys(g, s).endsWith("g"))
    }

    @Test
    fun aKeySweptOnTheWayToTheMarkIsKeptOnlyWhenItMayEndTheWord() {
        // n-g-s is nearly straight: g may follow n, so it stays
        val (g1, s1) = split("tangs")
        assertEquals(setOf(1), s1.picked)
        assertTrue(keptKeys(g1, s1).endsWith("ng"))
        // h to f sweeps over g, which may not follow h
        val (g2, s2) = split("thanhf")
        assertEquals(setOf(2), s2.picked)
        assertTrue(keptKeys(g2, s2).endsWith("nh"))
    }

    @Test
    fun aLoopOnDAtTheStartIsTheStroke() {
        assertEquals(setOf(9), split("d*i").second.picked) // đi
        assertEquals("di", letters(split("d*i").second))
    }

    @Test
    fun loopsOnEAndOAreTheCircumflex() {
        assertEquals(setOf(6), split("ve*t").second.picked)
        assertEquals(setOf(1, 6), split("to*ts").second.picked)
    }

    @Test
    fun aLoopReachesTheModelAsOnePoint() {
        val (g, s) = split("tha*n")
        val loop = TelexSwipe.loops(g.xs, g.ys, g.xs.size, 100f).single()
        assertEquals(1, loop.count { s.keep[it] })
    }

    @Test
    fun aLetterOnTheBorderWithAFinalConsonantStaysALetter() {
        // Ends between g and f: g can end a word, so it is no huyền
        assertEquals(null, TelexSwipe.markOf(setOf('f', 'g'), setOf('a')))
        assertEquals(1, TelexSwipe.markOf(setOf('s', 'd'), setOf('a')))
        assertEquals(null, TelexSwipe.markOf(setOf('a', 's'), setOf('a')))
    }

    @Test
    fun emptyGesture() {
        val s = TelexSwipe.split(IntArray(0), IntArray(0), IntArray(0), 0, keyBoxes, 100f)
        assertEquals(setOf<Int>(), s.picked)
        assertEquals(0, s.keep.size)
    }
}
