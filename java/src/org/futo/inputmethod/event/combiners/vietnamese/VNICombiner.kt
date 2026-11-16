package org.futo.inputmethod.event.combiners.vietnamese

import android.text.TextUtils
import org.futo.inputmethod.event.Combiner
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.latin.common.Constants
import java.util.ArrayList

class VNICombiner: Combiner {
    private val buffer = StringBuilder() // holds a single Vietnamese word/syllable

    override fun processEvent(
        previousEvents: ArrayList<Event?>?,
        event: Event?
    ): Event {
        if (event == null) return Event.createNotHandledEvent()
        val resumed = event.eventType == Event.EVENT_TYPE_INPUT_KEYPRESS_RESUMED
        if (event.eventType != Event.EVENT_TYPE_INPUT_KEYPRESS && !resumed) return event

        val keypress = event.mCodePoint.toChar()

        // The normal ASCII digits are left untouched by the combiner and always result in digits
        // being committed to the output. On the other hand, fullwidth digits are intercepted by
        // this combiner, converted into ASCII digits, and sent to the VNI converter.
        // This lets the user explicitly enter numbers that will not get converted into diacritics.
        // For example, if ASCII '1' (U+0031 DIGIT ONE) is given to this combiner, it will always
        // output an ASCII '1' (U+0031).
        // But if a fullwidth '１' (U+FF11 FULLWIDTH DIGIT ONE) is given to this combiner, it will be
        // converted to an ASCII '1' (U+0031) and given to the VNI converter, where it might result
        // in an acute accent being placed over a letter.
        // So, the input sequence [V][i][e][t][U+FF15][U+FF16] will result in the output "Việt"
        if (keypress.code in 0xFF10..0xFF19) {
            buffer.append((keypress.code - 0xFEE0).toChar())
            return Event.createConsumedEvent(event)
        }

        // A resumed word (a swiped word, or one the cursor came back to) goes into the buffer as its
        // VNI keys ("tháng" -> "tha1ng"), so tone keys pressed afterwards still apply to it.
        if (resumed) {
            VNI.keysFor(keypress)?.let {
                buffer.append(it)
                return Event.createConsumedEvent(event)
            }
            // Not typed with VNI (a hyphen, an apostrophe): hand on what the buffer holds as text,
            // followed by it, so the word keeps its order
            val text = getCombiningStateFeedback().toString() + keypress
            buffer.clear()
            return Event.createSoftwareTextEvent(text, Event.NOT_A_KEY_CODE)
        }

        if (!(keypress in 'A'..'Z' || keypress in 'a'..'z')) {
            if (!TextUtils.isEmpty(buffer)) {
                if (event.mKeyCode == Constants.CODE_DELETE) {
                    buffer.setLength(buffer.length - 1)
                    return Event.createConsumedEvent(event)
                }
            }

            if(!event.isFunctionalKeyEvent) return Event.createResetEvent(event)
            return event
        }

        buffer.append(keypress)
        return Event.createConsumedEvent(event)
    }

    override fun getCombiningStateFeedback(): CharSequence? =
        try{
            VNI.VNIToVietnamese(buffer.toString())
        } catch(e: Exception) {
            buffer
        }

    override fun reset() {
        buffer.clear()
    }
}