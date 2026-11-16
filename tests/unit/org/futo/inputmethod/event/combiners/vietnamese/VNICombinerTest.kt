package org.futo.inputmethod.event.combiners.vietnamese

import org.futo.inputmethod.event.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class VNICombinerTest {
    private val combiner = VNICombiner()

    /** Feeds [word] the way WordComposer.setBatchInputWord does after a swipe. */
    private fun resume(word: String) {
        for (ch in word) combiner.processEvent(null, Event.createEventForCodePointFromUnknownSource(ch.code))
    }

    private fun tapToneKey(digit: Int) {
        combiner.processEvent(null, Event.createSoftwareKeypressEvent(0xFF10 + digit, 0, 0, 0, false))
    }

    @Test
    fun toneKeysApplyToASwipedWord() {
        resume("tháng")
        assertEquals("tháng", combiner.combiningStateFeedback.toString())
        tapToneKey(8)
        assertEquals("thắng", combiner.combiningStateFeedback.toString())
    }

    @Test
    fun aSwipedWordWithoutMarksTakesTheToneKeys() {
        resume("That")
        tapToneKey(6)
        tapToneKey(5)
        assertEquals("Thật", combiner.combiningStateFeedback.toString())
    }
}
