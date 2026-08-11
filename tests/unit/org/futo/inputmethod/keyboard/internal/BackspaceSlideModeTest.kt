package org.futo.inputmethod.keyboard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the delete-slide granularity switch.
 *
 * Word-per-step is 32, character-per-step is 16 throughout, matching the app's actual
 * [sPointerBigStep]/[sPointerStep] ratio (32dp/16dp) - the ratio matters here, not the absolute
 * values, since it decides how many character-steps a single word-step's worth of reversal becomes.
 */
class BackspaceSlideModeTest {

    private val word = 32
    private val char = 16

    @Test
    fun `no movement is no step`() {
        val r = BackspaceSlideMode.next(x = 100, anchorX = 100, latchedToOther = false,
            lastStepSign = 0, baseStepPx = word, otherStepPx = char)
        assertEquals(0, r.steps)
        assertEquals(100, r.newAnchorX)
        assertFalse(r.latchedToOther)
    }

    @Test
    fun `movement under one step is not yet a step`() {
        val r = BackspaceSlideMode.next(x = 131, anchorX = 100, latchedToOther = false,
            lastStepSign = 0, baseStepPx = word, otherStepPx = char)
        assertEquals(0, r.steps)
    }

    @Test
    fun `the first step commits at the base granularity`() {
        val r = BackspaceSlideMode.next(x = 68, anchorX = 100, latchedToOther = false,
            lastStepSign = 0, baseStepPx = word, otherStepPx = char)
        assertEquals(-1, r.steps)
        assertEquals(68, r.newAnchorX)
        assertFalse(r.latchedToOther)
        assertEquals(-1, r.lastStepSign)
    }

    @Test
    fun `continuing the same direction keeps stepping at the base granularity`() {
        var anchor = 100
        var latched = false
        var sign = 0

        var r = BackspaceSlideMode.next(anchor - word, anchor, latched, sign, word, char)
        anchor = r.newAnchorX; latched = r.latchedToOther; sign = r.lastStepSign

        r = BackspaceSlideMode.next(anchor - word, anchor, latched, sign, word, char)

        assertEquals(-1, r.steps)
        assertFalse(r.latchedToOther)
    }

    /**
     * The main behaviour under test: reversing past a full base-granularity step switches this
     * gesture to the other granularity, and that same crossing is measured at the finer precision
     * rather than costing a whole base-step's worth of undo.
     */
    @Test
    fun `reversing past a base step latches to the other granularity`() {
        // One word-step left, establishing a direction to reverse out of.
        val first = BackspaceSlideMode.next(x = 68, anchorX = 100, latchedToOther = false,
            lastStepSign = 0, baseStepPx = word, otherStepPx = char)

        // Cross one word-step's distance back to the right (68 + 32 = 100).
        val bounce = BackspaceSlideMode.next(x = 100, anchorX = first.newAnchorX,
            latchedToOther = first.latchedToOther, lastStepSign = first.lastStepSign,
            baseStepPx = word, otherStepPx = char)

        assertTrue(bounce.latchedToOther)
        // 32px of reversal at 16px-per-character-step is 2 character-steps, not 1 word-step.
        assertEquals(2, bounce.steps)
        assertEquals(1, bounce.lastStepSign)
    }

    /** Once latched, further reversals do not switch back - see the class doc on why it is a latch. */
    @Test
    fun `a second reversal does not unlatch`() {
        var anchor = 100
        var latched = false
        var sign = 0

        // Left far enough to establish direction, then bounce right far enough to latch.
        var r = BackspaceSlideMode.next(anchor - word, anchor, latched, sign, word, char)
        anchor = r.newAnchorX; latched = r.latchedToOther; sign = r.lastStepSign
        r = BackspaceSlideMode.next(anchor + word, anchor, latched, sign, word, char)
        anchor = r.newAnchorX; latched = r.latchedToOther; sign = r.lastStepSign
        assertTrue(latched)

        // Reverse again, left, far enough to cross a character-step.
        r = BackspaceSlideMode.next(anchor - char, anchor, latched, sign, word, char)

        assertTrue("a second reversal must not unlatch", r.latchedToOther)
        assertEquals(-1, r.steps)
    }

    @Test
    fun `a reversal too small to cross a base step does not latch`() {
        val first = BackspaceSlideMode.next(x = 68, anchorX = 100, latchedToOther = false,
            lastStepSign = 0, baseStepPx = word, otherStepPx = char)

        // Only 20px back - short of the 32px base step, even though it is more than a char step.
        val r = BackspaceSlideMode.next(x = 88, anchorX = first.newAnchorX,
            latchedToOther = first.latchedToOther, lastStepSign = first.lastStepSign,
            baseStepPx = word, otherStepPx = char)

        assertEquals(0, r.steps)
        assertFalse(r.latchedToOther)
        // Direction bookkeeping from the committed step must survive an event that commits nothing.
        assertEquals(-1, r.lastStepSign)
    }

    @Test
    fun `the very first movement of a gesture is never treated as a reversal`() {
        // lastStepSign = 0 means nothing has committed yet, however this call is invoked.
        val r = BackspaceSlideMode.next(x = 68, anchorX = 100, latchedToOther = false,
            lastStepSign = 0, baseStepPx = word, otherStepPx = char)
        assertFalse(r.latchedToOther)
    }

    /** The mirror case: a character-primary gesture bouncing out to word granularity. */
    @Test
    fun `the base and other granularities can be swapped`() {
        val first = BackspaceSlideMode.next(x = 84, anchorX = 100, latchedToOther = false,
            lastStepSign = 0, baseStepPx = char, otherStepPx = word)
        assertEquals(-1, first.steps)

        val bounce = BackspaceSlideMode.next(x = 116, anchorX = first.newAnchorX,
            latchedToOther = first.latchedToOther, lastStepSign = first.lastStepSign,
            baseStepPx = char, otherStepPx = word)

        assertTrue(bounce.latchedToOther)
        assertEquals(1, bounce.steps)
    }
}
