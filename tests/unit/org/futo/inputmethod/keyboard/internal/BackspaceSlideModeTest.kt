package org.futo.inputmethod.keyboard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for delete-slide granularity.
 *
 * Word steps are 32 and character steps 16 throughout, matching the app's real 32dp/16dp. The ratio
 * is what matters: a word step is *shorter* than the letters it removes, which is exactly why the
 * gesture cannot start in word mode - a slide meant to trim a few characters would swallow a whole
 * word before travelling as far as those characters would have needed.
 */
class BackspaceSlideModeTest {

    private val char = 16
    private val word = 32

    private val words = BackspaceSlideMode.Config(
        charStepPx = char, wordStepPx = word,
        escalateAfterPx = word * 2, wordsAllowed = true
    )
    private val charsOnly = words.copy(wordsAllowed = false)

    private fun start(at: Int = 100) = BackspaceSlideMode.State(anchorX = at, startX = at)

    /** Drags left to [to], feeding intermediate positions so escalation is seen as it happens. */
    private fun dragTo(
        from: BackspaceSlideMode.State,
        to: Int,
        config: BackspaceSlideMode.Config = words,
        stepPx: Int = 4
    ): Pair<BackspaceSlideMode.State, MutableList<BackspaceSlideMode.Result>> {
        var state = from
        val seen = mutableListOf<BackspaceSlideMode.Result>()
        val dir = if (to < state.anchorX) -1 else 1
        var x = from.anchorX
        // Clamped to the target rather than stepping past it: overshooting by a few pixels is
        // enough to cross an escalation threshold the test meant to stop short of.
        while (x != to) {
            x = if (dir < 0) maxOf(to, x - stepPx) else minOf(to, x + stepPx)
            val r = BackspaceSlideMode.next(x, state, config)
            state = r.state
            if (r.steps != 0) seen.add(r)
        }
        return state to seen
    }

    // ---------------------------------------------------------------- starting fine

    @Test
    fun `a slide begins at character granularity`() {
        val r = BackspaceSlideMode.next(100 - char, start(), words)
        assertEquals(-1, r.steps)
        assertFalse("the first step must not be a word", r.wordMode)
    }

    @Test
    fun `movement under one character step does nothing`() {
        val r = BackspaceSlideMode.next(100 - (char - 1), start(), words)
        assertEquals(0, r.steps)
    }

    /**
     * The reported bug. Two words' worth of travel used to be four word-steps of deletion; it is
     * now four characters, which is what a short slide almost always means.
     */
    @Test
    fun `a short slide stays in characters`() {
        // One pixel short of the escalation threshold - the most a slide can travel and still be
        // treated as a precision edit.
        val (_, seen) = dragTo(start(), 100 - (word * 2 - 1))
        assertTrue("nothing in a short slide may be a word step", seen.none { it.wordMode })
        assertEquals("63px of travel is three character steps", -3, seen.sumOf { it.steps })
    }

    // ---------------------------------------------------------------- escalating

    @Test
    fun `words take over once the slide is clearly bulk deletion`() {
        val (state, seen) = dragTo(start(), 100 - word * 4)
        assertTrue("a long slide must reach word granularity", seen.any { it.wordMode })
        assertTrue(state.escalated)
    }

    @Test
    fun `escalation does not happen before the threshold`() {
        val (state, _) = dragTo(start(), 100 - word * 2 + 2)
        assertFalse(state.escalated)
    }

    @Test
    fun `escalation is measured from where the finger went down, not from the moving anchor`() {
        // The anchor advances with every committed step, so measuring from it would mean the
        // threshold is never reached however far the slide goes.
        val (state, _) = dragTo(start(), 100 - word * 3)
        assertTrue(state.escalated)
    }

    // ---------------------------------------------------------------- reversing

    @Test
    fun `reversing after escalation drops back to characters for good`() {
        val (escalated, _) = dragTo(start(), 100 - word * 4)
        assertTrue(escalated.escalated)

        // Bounce back the other way, far enough to cross a step.
        val bounce = BackspaceSlideMode.next(escalated.anchorX + word, escalated, words)

        assertTrue(bounce.state.latchedFine)
        assertFalse("after a reversal the gesture is fine again", bounce.wordMode)
        assertEquals("the bounce is felt at character precision", 2, bounce.steps)
    }

    @Test
    fun `a second reversal does not go back to words`() {
        val (escalated, _) = dragTo(start(), 100 - word * 4)
        val bounce = BackspaceSlideMode.next(escalated.anchorX + word, escalated, words)

        val again = BackspaceSlideMode.next(bounce.state.anchorX - char, bounce.state, words)

        assertTrue(again.state.latchedFine)
        assertFalse(again.wordMode)
        assertEquals(-1, again.steps)
    }

    @Test
    fun `once fine, travelling further does not go back to words`() {
        val (escalated, _) = dragTo(start(), 100 - word * 4)
        val bounce = BackspaceSlideMode.next(escalated.anchorX + word, escalated, words)

        val (far, seen) = dragTo(bounce.state, bounce.state.anchorX - word * 6)

        // `escalated` stays true - it records that the threshold was passed - but latchedFine is
        // what decides granularity from here, and it outranks it.
        assertTrue("the reversal must keep holding", far.latchedFine)
        assertTrue("no step after a reversal may be a word", seen.none { it.wordMode })
        assertTrue(seen.isNotEmpty())
    }

    /** Reversing while still fine is just movement the other way - there is nothing to switch to. */
    @Test
    fun `reversing before escalation changes no granularity`() {
        val first = BackspaceSlideMode.next(100 - char, start(), words)
        val back = BackspaceSlideMode.next(first.state.anchorX + char, first.state, words)

        assertEquals(1, back.steps)
        assertFalse(back.wordMode)
        assertFalse(back.state.latchedFine)
    }

    // ---------------------------------------------------------------- the character-only setting

    @Test
    fun `words never appear when the user asked for characters only`() {
        val (state, seen) = dragTo(start(), 100 - word * 8, config = charsOnly)
        assertFalse(state.escalated)
        assertTrue(seen.isNotEmpty())
        assertTrue("character mode must never escalate", seen.none { it.wordMode })
    }

    // ---------------------------------------------------------------- bookkeeping

    @Test
    fun `a step that commits nothing leaves the direction alone`() {
        val first = BackspaceSlideMode.next(100 - char, start(), words)
        assertEquals(-1, first.state.lastStepSign)

        val nothing = BackspaceSlideMode.next(first.state.anchorX - 3, first.state, words)
        assertEquals(0, nothing.steps)
        assertEquals("direction survives an event that commits nothing",
            -1, nothing.state.lastStepSign)
    }

    @Test
    fun `the anchor advances by exactly what was committed`() {
        val r = BackspaceSlideMode.next(100 - char * 3, start(), words)
        assertEquals(-3, r.steps)
        assertEquals(100 - char * 3, r.state.anchorX)
    }
}
