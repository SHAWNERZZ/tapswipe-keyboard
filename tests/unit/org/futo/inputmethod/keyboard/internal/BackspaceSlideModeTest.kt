package org.futo.inputmethod.keyboard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for delete-slide granularity.
 *
 * The property under test throughout is the exchange rate: a given distance consumes the same
 * amount of *text* whether it is being deleted by word or by character. Word mode changes what is
 * selected in one go, never how fast the document is consumed.
 *
 * A character step is 16 here, matching the app's 16dp. Slides run leftward, as deletion does.
 */
class BackspaceSlideModeTest {

    private val char = 16

    private val words = BackspaceSlideMode.Config(charStepPx = char, wordsAllowed = true)
    private val charsOnly = BackspaceSlideMode.Config(charStepPx = char, wordsAllowed = false)

    private fun start(at: Int = 1000) = BackspaceSlideMode.State(anchorX = at)

    /** Feeds one event and, if it took a word, charges that word [wordLength] characters. */
    private fun event(
        state: BackspaceSlideMode.State, x: Int, wordLength: Int = 5
    ): BackspaceSlideMode.Result {
        val r = BackspaceSlideMode.next(x, state, words)
        if (r.steps == 0 || !r.wordMode) return r
        return r.copy(state = BackspaceSlideMode.chargeWord(r.state, wordLength, words))
    }

    // ---------------------------------------------------------------- words, from the first step

    /**
     * The correction that produced this design. Word deletion must be available immediately: a
     * slide that has to travel before words engage cannot clear a sentence quickly, which is the
     * only thing word mode is for.
     */
    @Test
    fun `the first movement in word mode takes a word`() {
        val r = BackspaceSlideMode.next(1000 - char, start(), words)
        assertTrue("word mode must engage from the first step", r.wordMode)
        assertEquals("a word step is a direction, not a count", -1, r.steps)
    }

    @Test
    fun `movement under one character does nothing`() {
        assertEquals(0, BackspaceSlideMode.next(1000 - (char - 1), start(), words).steps)
    }

    // ---------------------------------------------------------------- the exchange rate

    /**
     * The heart of it. A five-letter word costs five characters of travel, so the next word does
     * not arrive until the finger has moved as far as deleting those letters would have taken.
     */
    @Test
    fun `a word costs its own length in travel`() {
        val after = event(start(), 1000 - char, wordLength = 5).state

        assertEquals("the next word must not arrive early",
            0, BackspaceSlideMode.next(1000 - char * 4, after, words).steps)
        assertEquals("and must arrive once the length has been walked off",
            -1, BackspaceSlideMode.next(1000 - char * 7, after, words).steps)
    }

    @Test
    fun `a long word costs more travel than a short one`() {
        val shortWord = event(start(), 1000 - char, wordLength = 3).state
        val longWord = event(start(), 1000 - char, wordLength = 12).state

        val x = 1000 - char * 6
        assertEquals("a short word should already be paid for",
            -1, BackspaceSlideMode.next(x, shortWord, words).steps)
        assertEquals("a long word should still be being paid for",
            0, BackspaceSlideMode.next(x, longWord, words).steps)
    }

    /**
     * Sweeping a phrase by word takes the same travel as sweeping it by character, less the word
     * currently selected - the slide runs exactly one word ahead of what it has paid for, which is
     * what makes the first word immediate. Everything after it costs the length of the one before.
     *
     * This is the property a flat per-word step broke, and the reason word mode used to outrun the
     * finger no matter how long the words were.
     */
    @Test
    fun `each word after the first costs the previous word's length`() {
        val lengths = listOf(4, 6, 3, 5)

        var state = start()
        var x = 1000
        var taken = 0
        // Creep leftward one pixel at a time, so the travel at which each word lands is exact.
        while (taken < lengths.size) {
            x -= 1
            val r = event(state, x, lengths[taken])
            state = r.state
            if (r.steps != 0) taken++
            assertTrue("a runaway slide would never terminate", x > 1000 - 1000)
        }

        // One character to take the first word, then the length of each word to earn the next.
        val expected = char + lengths.dropLast(1).sum() * char
        assertEquals("word travel must track the text actually swept", expected, 1000 - x)
    }

    @Test
    fun `a word the editor reports as costing nothing is still charged`() {
        // Otherwise an almost stationary finger consumes the document one event at a time.
        val after = event(start(), 1000 - char, wordLength = 0).state
        assertTrue("a zero-length word must still owe something", after.debtPx > 0)
    }

    /** Travel past what was owed counts toward the next word rather than being forgiven. */
    @Test
    fun `overshooting a debt is not wasted`() {
        val after = event(start(), 1000 - char, wordLength = 4).state
        // Jump well past the four characters owed.
        val r = BackspaceSlideMode.next(1000 - char * 9, after, words)

        assertEquals(-1, r.steps)
        assertEquals("the anchor should move by the debt, not to the finger",
            after.anchorX - char * 4, r.state.anchorX)
    }

    // ---------------------------------------------------------------- reversing

    @Test
    fun `reversing drops to character precision for the rest of the gesture`() {
        val after = event(start(), 1000 - char, wordLength = 6).state

        val back = BackspaceSlideMode.next(after.anchorX + char, after, words)

        assertTrue(back.state.latchedFine)
        assertFalse("after a reversal the gesture is fine", back.wordMode)
        assertEquals("and moves by characters", 1, back.steps)
    }

    /**
     * The failure this model was rewritten to avoid. A word longer than the travel so far leaves a
     * debt, and if that debt were carried by moving the anchor past the finger, simply continuing
     * in the same direction would read as movement backwards and latch the gesture fine.
     */
    @Test
    fun `an unpaid debt is not mistaken for a reversal`() {
        val after = event(start(), 1000 - char, wordLength = 12).state

        // Still inside the debt, continuing the same way.
        val r = BackspaceSlideMode.next(1000 - char * 3, after, words)

        assertFalse("continuing into a debt is not a bounce", r.state.latchedFine)
        assertTrue(r.wordMode)
        assertEquals(0, r.steps)
    }

    @Test
    fun `a second reversal does not go back to words`() {
        var state = event(start(), 1000 - char, wordLength = 6).state
        state = BackspaceSlideMode.next(state.anchorX + char, state, words).state

        val again = BackspaceSlideMode.next(state.anchorX - char, state, words)

        assertTrue(again.state.latchedFine)
        assertFalse(again.wordMode)
        assertEquals(-1, again.steps)
    }

    @Test
    fun `once fine, continuing further stays fine`() {
        var state = event(start(), 1000 - char, wordLength = 6).state
        state = BackspaceSlideMode.next(state.anchorX + char, state, words).state

        val far = BackspaceSlideMode.next(state.anchorX - char * 4, state, words)

        assertFalse("no step after a reversal may be a word", far.wordMode)
        assertEquals(-4, far.steps)
    }

    @Test
    fun `jitter below a character does not count as a reversal`() {
        val after = event(start(), 1000 - char, wordLength = 6).state
        val r = BackspaceSlideMode.next(after.anchorX + (char - 1), after, words)

        assertFalse(r.state.latchedFine)
        assertEquals(0, r.steps)
    }

    @Test
    fun `a rightward slide works the same way`() {
        val r = BackspaceSlideMode.next(1000 + char, start(), words)
        assertEquals(1, r.steps)
        assertEquals(1, r.state.direction)
    }

    // ---------------------------------------------------------------- the character-only setting

    @Test
    fun `words never appear when the user asked for characters only`() {
        val r = BackspaceSlideMode.next(1000 - char * 4, start(), charsOnly)
        assertFalse(r.wordMode)
        assertEquals(-4, r.steps)
        assertEquals(1000 - char * 4, r.state.anchorX)
    }
}
