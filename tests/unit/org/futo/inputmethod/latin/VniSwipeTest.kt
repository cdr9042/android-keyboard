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

    /** Straight lines between [corners], sampled every 10 px like a real gesture. */
    private fun picked(vararg corners: Pair<Int, Int>): Set<Int> {
        val xs = mutableListOf(corners[0].first)
        val ys = mutableListOf(corners[0].second)
        for (k in 1 until corners.size) {
            val (x0, y0) = corners[k - 1]
            val (x1, y1) = corners[k]
            val steps = maxOf(abs(x1 - x0), abs(y1 - y0)) / 10
            for (s in 1..steps) {
                xs.add(x0 + (x1 - x0) * s / steps)
                ys.add(y0 + (y1 - y0) * s / steps)
            }
        }
        val x = xs.toIntArray()
        val y = ys.toIntArray()
        return VniSwipe.pickedTones(x, y, VniSwipe.toneDigitsAt(x, y, x.size, toneAt), minStep = 30f)
    }

    @Test
    fun turnsPickTonesAndKeysSweptOverAreIgnored() {
        // Up to 6, along the row to 1, down to the letters: 2..5 are only swept over
        assertEquals(setOf(1, 6), picked(550 to 400, 550 to 50, 50 to 50, 50 to 400))
    }

    @Test
    fun goingUpAndBackDownPicksThatTone() {
        assertEquals(setOf(6), picked(550 to 400, 550 to 30, 550 to 400))
    }

    @Test
    fun startingOrEndingOnAToneKeyPicksIt() {
        assertEquals(setOf(3), picked(250 to 50, 250 to 400))
        assertEquals(setOf(8), picked(250 to 400, 750 to 50))
    }

    @Test
    fun noToneRowNoTones() {
        assertEquals(setOf<Int>(), picked(100 to 400, 800 to 300))
        assertEquals(setOf<Int>(), VniSwipe.pickedTones(IntArray(0), IntArray(0), IntArray(0), 30f))
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
