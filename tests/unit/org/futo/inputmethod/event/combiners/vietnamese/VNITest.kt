package org.futo.inputmethod.event.combiners.vietnamese

import org.junit.Assert.assertEquals
import org.junit.Test

class VNITest {
    /** What VNICombiner's buffer holds after being fed [word] one character at a time. */
    private fun keysFor(word: String) = word.map { VNI.keysFor(it) ?: "?" }.joinToString("")

    @Test
    fun markedLettersBecomeTheirVniKeys() {
        assertEquals("a1", VNI.keysFor('á'))
        assertEquals("A1", VNI.keysFor('Á'))
        assertEquals("a81", VNI.keysFor('ắ'))
        assertEquals("o72", VNI.keysFor('ờ'))
        assertEquals("u7", VNI.keysFor('ư'))
        assertEquals("d9", VNI.keysFor('đ'))
        assertEquals("D9", VNI.keysFor('Đ'))
        assertEquals("b", VNI.keysFor('b'))
        assertEquals(null, VNI.keysFor('1'))
        assertEquals(null, VNI.keysFor('ç')) // cedilla is not a VNI mark
    }

    @Test
    fun aSwipedWordKeepsItsMarksAndTakesMoreFromTheToneKeys() {
        // Swiped "tháng", then the ă key (8): thắng. Then the grave key (2) replaces the acute.
        assertEquals("tháng", VNI.VNIToVietnamese(keysFor("tháng")))
        assertEquals("thắng", VNI.VNIToVietnamese(keysFor("tháng") + "8"))
        assertEquals("thằng", VNI.VNIToVietnamese(keysFor("tháng") + "82"))
        assertEquals("đường", VNI.VNIToVietnamese(keysFor("dương") + "92"))
        assertEquals("Thật", VNI.VNIToVietnamese(keysFor("That") + "65"))
    }
}
