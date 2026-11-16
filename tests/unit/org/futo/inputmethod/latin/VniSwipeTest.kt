package org.futo.inputmethod.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VniSwipeTest {
    @Test
    fun toneCodesAreFullwidthDigitsOneToNine() {
        assertTrue(VniSwipe.isToneCode(0xFF11))
        assertTrue(VniSwipe.isToneCode(0xFF19))
        assertFalse(VniSwipe.isToneCode(0xFF10)) // fullwidth zero is not a ranking tone
        assertFalse(VniSwipe.isToneCode('1'.code)) // ASCII digit
        assertFalse(VniSwipe.isToneCode('a'.code))
        assertEquals(1, VniSwipe.toneDigit(0xFF11))
        assertEquals(9, VniSwipe.toneDigit(0xFF19))
    }

    @Test
    fun markDigitsOfWords() {
        assertEquals(setOf<Int>(), VniSwipe.markDigits("viet"))
        assertEquals(setOf(5, 6), VniSwipe.markDigits("việt"))
        assertEquals(setOf(5, 6), VniSwipe.markDigits("Việt"))
        assertEquals(setOf(1, 6), VniSwipe.markDigits("vết"))
        assertEquals(setOf(9), VniSwipe.markDigits("đi"))
        assertEquals(setOf(2), VniSwipe.markDigits("mà"))
        assertEquals(setOf(3), VniSwipe.markDigits("hỏi"))
        assertEquals(setOf(4), VniSwipe.markDigits("ngã"))
        assertEquals(setOf(1), VniSwipe.markDigits("á"))
        assertEquals(setOf(8), VniSwipe.markDigits("ă"))
        assertEquals(setOf(5, 7, 9), VniSwipe.markDigits("được"))
    }

    // Fake keyboard: tone row is y in 0 until 100, digit d spans x in (d - 1) * 100 until d * 100.
    // Everything below it is letters.
    private val toneAt = { x: Int, y: Int -> if (y in 0 until 100 && x in 0 until 900) x / 100 + 1 else 0 }

    /** A corner of a fake gesture; the finger rests [pauseMs] there before moving on. */
    private data class Corner(val x: Int, val y: Int, val pauseMs: Int = 0)

    private class Gesture(val xs: IntArray, val ys: IntArray, val ts: IntArray)

    /** Straight lines between [corners] at a fast swipe speed (2 px/ms), points every 5 ms. */
    private fun gesture(vararg corners: Corner): Gesture {
        val xs = mutableListOf<Int>()
        val ys = mutableListOf<Int>()
        val ts = mutableListOf<Int>()
        var t = 0
        fun add(x: Int, y: Int) { xs.add(x); ys.add(y); ts.add(t) }
        fun rest(c: Corner) {
            for (k in 1..c.pauseMs / 10) { t += 10; add(c.x, c.y) }
        }
        add(corners[0].x, corners[0].y)
        rest(corners[0])
        for (k in 1 until corners.size) {
            val a = corners[k - 1]
            val b = corners[k]
            val steps = maxOf(abs(b.x - a.x), abs(b.y - a.y)) / 10
            for (s in 1..steps) {
                t += 5
                add(a.x + (b.x - a.x) * s / steps, a.y + (b.y - a.y) * s / steps)
            }
            rest(b)
        }
        return Gesture(xs.toIntArray(), ys.toIntArray(), ts.toIntArray())
    }

    private fun split(g: Gesture): VniSwipe.ToneSplit =
        VniSwipe.splitTones(g.xs, g.ys, g.ts, VniSwipe.toneDigitsAt(g.xs, g.ys, g.xs.size, toneAt), keyWidth = 100f)

    private fun picked(vararg corners: Corner): Set<Int> = split(gesture(*corners)).picked

    @Test
    fun pausesPickTonesAndKeysSweptOverAreIgnored() {
        // Up to 6, along the row to 1, down to the letters: 2..5 are only swept over
        assertEquals(
            setOf(1, 6),
            picked(Corner(550, 400), Corner(550, 50, 150), Corner(50, 50, 150), Corner(50, 400))
        )
    }

    @Test
    fun leavingAPausedToneKeyAcrossItsNeighbourIgnoresTheNeighbour() {
        // The thắng swipe: rests on 1, then leaves down-right across the corner of 2
        assertEquals(setOf(1), picked(Corner(50, 400, 150), Corner(50, 50, 200), Corner(450, 300, 150)))
    }

    @Test
    fun startingOrEndingOnAToneKeyPicksIt() {
        assertEquals(setOf(3), picked(Corner(250, 50), Corner(250, 400)))
        assertEquals(setOf(8), picked(Corner(250, 400), Corner(750, 50)))
    }

    @Test
    fun noToneRowNoTones() {
        assertEquals(setOf<Int>(), picked(Corner(100, 400, 100), Corner(800, 300, 100)))
        val empty = VniSwipe.splitTones(IntArray(0), IntArray(0), IntArray(0), IntArray(0), 100f)
        assertEquals(setOf<Int>(), empty.picked)
        assertEquals(0, empty.keep.size)
    }

    @Test
    fun theWayUpToTheToneRowAndBackIsCutBetweenPausedLetters() {
        // Letters at y = 400; rests on a letter, goes up to 6, comes back down to rest on another letter.
        // Only the letter row may remain: everything between the two rests is dropped.
        val g = gesture(Corner(100, 400, 100), Corner(550, 400, 150), Corner(550, 50, 150), Corner(800, 400, 150))
        val keep = split(g).keep
        assertTrue(keep.first() && keep.last())
        for (i in keep.indices) {
            if (keep[i]) assertEquals("kept point $i", 400, g.ys[i])
        }
        assertTrue(keep.count { it } < keep.size)
    }

    @Test
    fun withoutAPausedLetterOnlyTheToneRowIsCut() {
        // Never rests on a letter before going up, so the way up is kept; the tone row never is
        val g = gesture(Corner(550, 300), Corner(550, 50, 150), Corner(550, 400, 150))
        val keep = split(g).keep
        val digits = VniSwipe.toneDigitsAt(g.xs, g.ys, g.xs.size, toneAt)
        assertTrue(keep.first())
        for (i in keep.indices) {
            if (digits[i] != 0) assertFalse(keep[i])
        }
    }

    @Test
    fun toneDigitsAtUsesTheLookup() {
        val xs = intArrayOf(10, 20, 30, 40)
        val ys = intArrayOf(0, 0, 0, 0)
        val result = VniSwipe.toneDigitsAt(xs, ys, 3) { x, _ -> if (x >= 20) 7 else 0 }
        assertEquals(listOf(0, 7, 7), result.toList()) // only the first `length` points
    }

    @Test
    fun rerankPutsCandidatesThatFitTheCrossedTonesFirst() {
        val words = listOf("viet", "việt", "vết", "vịt")
        // việt and vịt only use crossed marks, việt uses more of them; viet has no marks; vết uses 1
        assertEquals(listOf("việt", "vịt", "viet", "vết"), VniSwipe.rerank(words, setOf(5, 6)) { it })
        assertEquals(listOf("vịt", "viet", "việt", "vết"), VniSwipe.rerank(words, setOf(5)) { it })
    }

    @Test
    fun rerankCopesWithTonesCrossedOnTheWayBetweenTwoMeantKeys() {
        // Swiping m-e-^-'-n goes along the tone row from 6 to 1, so 2..5 are crossed as well
        val words = listOf("niên", "miên", "muốn", "mê-tín", "men", "mến")
        assertEquals(
            listOf("muốn", "mê-tín", "mến", "niên", "miên", "men"),
            VniSwipe.rerank(words, setOf(1, 2, 3, 4, 5, 6)) { it }
        )
    }

    @Test
    fun rerankBreaksTiesByHowWellThePathMatchesTheLetters() {
        // The m-e-^-'-n swipe: the model liked "men" letters best but scored it last overall
        val path = mapOf("miên" to -12.46f, "mê-tín" to -13.02f, "muốn" to -13.30f, "men" to -12.56f, "mến" to -12.56f)
        assertEquals(
            listOf("mến", "mê-tín", "muốn", "miên", "men"),
            VniSwipe.rerank(path.keys.toList(), setOf(1, 6), { path.getValue(it) }) { it }
        )
    }

    @Test
    fun markedVariantsUseExactlyThePickedMarks() {
        assertEquals(listOf("mến"), VniSwipe.markedVariants("men", setOf(1, 6)))
        assertTrue("việt" in VniSwipe.markedVariants("viet", setOf(5, 6)))
        assertTrue("đi" in VniSwipe.markedVariants("di", setOf(9)))
        assertTrue("được" in VniSwipe.markedVariants("duoc", setOf(5, 7, 9)))
        assertTrue("Mến" in VniSwipe.markedVariants("Men", setOf(1, 6)))
        for (v in VniSwipe.markedVariants("viet", setOf(5, 6))) assertEquals(setOf(5, 6), VniSwipe.markDigits(v))
    }

    @Test
    fun markedVariantsStartFromTheBaseLettersOfMarkedWords() {
        // Screenshots: the model offered thiết / dương / ương, the user swiped other marks
        assertTrue("thiệt" in VniSwipe.markedVariants("thiết", setOf(5, 6)))
        assertTrue("đường" in VniSwipe.markedVariants("dương", setOf(2, 7, 9)))
        assertTrue("ước" in VniSwipe.markedVariants("uoc", setOf(1, 7)))
        assertEquals("thiet", VniSwipe.baseLetters("thiết"))
        assertEquals("Duong", VniSwipe.baseLetters("Đường"))
    }

    @Test
    fun markedVariantsUseAtMostOneToneAndOnlyFittingLetters() {
        assertEquals(listOf("bá"), VniSwipe.markedVariants("ba", setOf(1)))
        assertEquals(listOf<String>(), VniSwipe.markedVariants("ba", setOf(1, 2))) // two tones
        assertEquals(listOf<String>(), VniSwipe.markedVariants("ba", setOf(7))) // horn does not go on a
        assertEquals(listOf<String>(), VniSwipe.markedVariants("xyz", setOf(6)))
        assertEquals(listOf<String>(), VniSwipe.markedVariants("men", emptySet()))
    }

    @Test
    fun rerankLeavesListAloneWhenNothingCrossedOrNothingMatches() {
        val words = listOf("viet", "việt", "vết")
        assertEquals(words, VniSwipe.rerank(words, emptySet()) { it })
        assertEquals(words, VniSwipe.rerank(words, setOf(9)) { it })
    }
}
