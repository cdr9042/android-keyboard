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
    fun aSharpTurnOnAToneKeyPicksItWithoutAPause() {
        // The thái swipe: up from a to 1 without stopping, turns there and heads down-right to i,
        // sweeping over 2..4 on the way out
        assertEquals(setOf(1), picked(Corner(50, 400, 150), Corner(50, 50), Corner(750, 300, 150)))
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
    fun aBriefSlowdownOnALetterBetweenTwoToneKeysIsNotARest() {
        // The thật swipe: rests on a, goes up to ^, dips into the letter row slowing a little
        // while turning, rests on the dot below, and ends on t below it. The dip is not a letter.
        val g = gesture(
            Corner(100, 400, 150), Corner(550, 50, 150), Corner(500, 130, 50),
            Corner(450, 50, 150), Corner(450, 150)
        )
        val s = split(g)
        assertEquals(setOf(5, 6), s.picked)
        for (i in s.keep.indices) {
            if (g.xs[i] == 500 && g.ys[i] == 130) assertFalse("dip point $i", s.keep[i])
        }
        assertTrue(s.keep.last())
    }

    @Test
    fun theWayUpIsCutFromASharpTurnOnALetterWithoutAPause() {
        // The thật swipe: from t turns sharply on a without stopping, up across letters to ^,
        // dips, rests on the dot below and ends on t. Nothing between a and the end reaches the model.
        val g = gesture(
            Corner(400, 300, 150), Corner(50, 400), Corner(550, 50, 150), Corner(500, 130, 50),
            Corner(450, 50, 150), Corner(450, 150)
        )
        val s = split(g)
        assertEquals(setOf(5, 6), s.picked)
        val atA = (g.xs.indices).first { g.xs[it] == 50 && g.ys[it] == 400 }
        val lastTone = VniSwipe.toneDigitsAt(g.xs, g.ys, g.xs.size, toneAt).indexOfLast { it != 0 }
        for (i in atA + 1..lastTone) assertFalse("point $i between a and the last tone key", s.keep[i])
        assertTrue(s.keep[atA])
        assertTrue(s.keep.last())
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

    // Fake keyboard for anchors: tone row 1..9 at y 0..99, then "qwertyuiop" and "asdfghjkl",
    // every key 100 x 100 starting at x = 0.
    private val keyBoxes = (1..9).map { VniSwipe.KeyBox(null, (it - 1) * 100, 0, it * 100, 100) } +
        "qwertyuiop".mapIndexed { i, c -> VniSwipe.KeyBox(c, i * 100, 100, i * 100 + 100, 200) } +
        "asdfghjkl".mapIndexed { i, c -> VniSwipe.KeyBox(c, i * 100, 200, i * 100 + 100, 300) }

    private fun anchors(vararg corners: Corner): List<VniSwipe.Anchor> {
        val g = gesture(*corners)
        return VniSwipe.anchors(g.xs, g.ys, g.ts, g.xs.size, keyBoxes, keyWidth = 100f)
    }

    private fun anchor(letters: String, start: Boolean = false, end: Boolean = false) =
        VniSwipe.Anchor(letters.toSet(), start, end)

    @Test
    fun anchorsAreTheStartRestsCornersAndEndButNotKeysSweptOver() {
        // t, rest on h, rest on a (sweeping g f d s fast), back up to end on t
        assertEquals(
            listOf(anchor("t", start = true), anchor("h"), anchor("a"), anchor("t", end = true)),
            anchors(Corner(450, 150, 100), Corner(550, 250, 100), Corner(50, 250, 100), Corner(450, 150))
        )
    }

    @Test
    fun aSharpCornerWithoutAPauseIsAnAnchor() {
        // e, straight through r and t, turns sharply on y without stopping, down to end on h
        assertEquals(
            listOf(anchor("e", start = true), anchor("y"), anchor("h", end = true)),
            anchors(Corner(250, 150, 100), Corner(550, 150), Corner(550, 250))
        )
    }

    @Test
    fun aPointOnTheBorderOfTwoKeysMayBeEither() {
        assertEquals(setOf('g', 'h'), VniSwipe.nearLetters(500, 250, keyBoxes))
        assertEquals(setOf('h'), VniSwipe.nearLetters(550, 250, keyBoxes))
        assertEquals(null, VniSwipe.nearLetters(450, 110, keyBoxes)) // close to tone key 5
    }

    @Test
    fun restsNextToTheToneRowAreLeftToTheToneLogic() {
        // d, rest on t right below tone key 5, end on j: the rest near the tone row is no anchor
        assertEquals(
            listOf(anchor("d", start = true), anchor("j", end = true)),
            anchors(Corner(250, 250, 100), Corner(450, 110, 150), Corner(650, 250))
        )
    }

    @Test
    fun wordsMustHoldTheAnchorsInOrderFromFirstToLastLetter() {
        val that = listOf(anchor("t", start = true), anchor("h"), anchor("a"), anchor("t", end = true))
        assertTrue(VniSwipe.fitsAnchors("thật", that))
        assertTrue(VniSwipe.fitsAnchors("that", that))
        assertFalse(VniSwipe.fitsAnchors("thay", that)) // does not end on t
        assertFalse(VniSwipe.fitsAnchors("hát", that)) // does not start on t
        assertFalse(VniSwipe.fitsAnchors("tát", that)) // no h

        val thang = listOf(anchor("t", start = true), anchor("hj"), anchor("a"), anchor("n"), anchor("gf", end = true))
        assertTrue(VniSwipe.fitsAnchors("thắng", thang))
        assertFalse(VniSwipe.fitsAnchors("thành", thang))

        val dang = listOf(anchor("d", start = true), anchor("a"), anchor("n"), anchor("gf", end = true))
        assertTrue(VniSwipe.fitsAnchors("đắng", dang))
        assertFalse(VniSwipe.fitsAnchors("doanh", dang))

        assertTrue(VniSwipe.fitsAnchors("tốt", listOf(anchor("t", start = true, end = true))))
        assertFalse(VniSwipe.fitsAnchors("to", listOf(anchor("t", start = true, end = true))))
        assertTrue(VniSwipe.fitsAnchors("anything", emptyList()))
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
    }

    @Test
    fun markedVariantsWithNoTonesGiveTheUnmarkedSpelling() {
        assertEquals(listOf("tam"), VniSwipe.markedVariants("tâm", emptySet()))
        assertEquals(listOf("nay"), VniSwipe.markedVariants("này", emptySet()))
        assertEquals(listOf("di"), VniSwipe.markedVariants("đi", emptySet()))
        assertEquals(listOf("men"), VniSwipe.markedVariants("men", emptySet()))
    }

    @Test
    fun endFixesEndTheBaseLettersOnTheLetterTheSwipeEndedOn() {
        assertEquals(listOf("that", "thant"), VniSwipe.endFixes("thân", setOf('t')))
        assertEquals(listOf("that", "thaht"), VniSwipe.endFixes("thah", setOf('t')))
        assertEquals(listOf("tht", "that"), VniSwipe.endFixes("tha", setOf('t')))
        assertEquals(listOf<String>(), VniSwipe.endFixes("", setOf('t')))
    }

    @Test
    fun theThatSwipeFindsThatWhenTheModelOnlyOffersThan() {
        // The model offered than/thân, the swipe ended on t with ^ and the dot below picked
        val found = VniSwipe.endFixes("thân", setOf('t')).flatMap { VniSwipe.markedVariants(it, setOf(5, 6)) }
        assertTrue("thật" in found)
    }

    @Test
    fun rerankPutsUnmarkedWordsFirstWhenNoToneWasCrossed() {
        assertEquals(
            listOf("tam", "nay", "tâm", "này"),
            VniSwipe.rerank(listOf("tâm", "tam", "này", "nay"), emptySet()) { it }
        )
        assertEquals(listOf("di", "đi"), VniSwipe.rerank(listOf("đi", "di"), emptySet()) { it })
    }

    @Test
    fun rerankLeavesListAloneWhenNoMarkedWordFitsTheCrossedTones() {
        val words = listOf("viet", "việt", "vết")
        assertEquals(words, VniSwipe.rerank(words, setOf(9)) { it })
    }
}
