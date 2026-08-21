package org.futo.inputmethod.keyboard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for delete-slide distance.
 *
 * The property under test throughout is the exchange rate: a given distance removes the same amount
 * of text in either mode. Word mode changes what is selected in one go, never how fast the document
 * is consumed.
 *
 * A character step is 16 here, matching the app's 16dp. Slides run leftward, as deletion does.
 */
class BackspaceSlideModeTest {

    private val char = 16

    private val words = BackspaceSlideMode.Config(charStepPx = char, wordMode = true)
    private val chars = BackspaceSlideMode.Config(charStepPx = char, wordMode = false)

    private fun start(at: Int = 1000) = BackspaceSlideMode.State(anchorX = at)

    /** Feeds one event and, if it took a word, charges that word [wordLength] characters. */
    private fun event(
        state: BackspaceSlideMode.State, x: Int, wordLength: Int = 5
    ): BackspaceSlideMode.Result {
        val r = BackspaceSlideMode.next(x, state, words)
        if (r.steps == 0) return r
        return r.copy(state = BackspaceSlideMode.chargeWord(r.state, wordLength, words))
    }

    // ---------------------------------------------------------------- character mode

    @Test
    fun `a plain slide moves by characters`() {
        val r = BackspaceSlideMode.next(1000 - char * 3, start(), chars)
        assertEquals(-3, r.steps)
        assertEquals(1000 - char * 3, r.state.anchorX)
    }

    @Test
    fun `movement under one character does nothing`() {
        assertEquals(0, BackspaceSlideMode.next(1000 - (char - 1), start(), chars).steps)
    }

    @Test
    fun `a plain slide never takes a word, however far it goes`() {
        // The mode is fixed by the entry gesture, so distance cannot promote it.
        val r = BackspaceSlideMode.next(1000 - char * 40, start(), chars)
        assertEquals(-40, r.steps)
    }

    @Test
    fun `a plain slide moves back by characters`() {
        val out = BackspaceSlideMode.next(1000 - char * 4, start(), chars)
        val back = BackspaceSlideMode.next(out.state.anchorX + char * 2, out.state, chars)
        assertEquals(2, back.steps)
    }

    // ---------------------------------------------------------------- word mode

    /** Word deletion is available from the first movement. Waiting for distance defeats the point. */
    @Test
    fun `the first movement in word mode takes a word`() {
        val r = BackspaceSlideMode.next(1000 - char, start(), words)
        assertEquals("a word step is a direction, not a count", -1, r.steps)
    }

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

    @Test
    fun `each word after the first costs the previous word's length`() {
        val lengths = listOf(4, 6, 3, 5)

        var state = start()
        var x = 1000
        var taken = 0
        while (taken < lengths.size) {
            x -= 1
            val r = event(state, x, lengths[taken])
            state = r.state
            if (r.steps != 0) taken++
            assertTrue("a runaway slide would never terminate", x > 0)
        }

        // One character to take the first word, then the length of each word to earn the next.
        val expected = char + lengths.dropLast(1).sum() * char
        assertEquals("word travel must track the text actually swept", expected, 1000 - x)
    }

    @Test
    fun `a word the editor reports as costing nothing is still charged`() {
        val after = event(start(), 1000 - char, wordLength = 0).state
        assertTrue("a zero-length word must still owe something", after.debtPx > 0)
    }

    @Test
    fun `overshooting a debt is not wasted`() {
        val after = event(start(), 1000 - char, wordLength = 4).state
        val r = BackspaceSlideMode.next(1000 - char * 9, after, words)

        assertEquals(-1, r.steps)
        assertEquals("the anchor should move by the debt, not to the finger",
            after.anchorX - char * 4, r.state.anchorX)
    }

    /**
     * Continuing into an expensive word must not look like turning around. An earlier design
     * carried the debt by moving the anchor past the finger, which produced exactly that.
     */
    @Test
    fun `an unpaid debt is not mistaken for a reversal`() {
        val after = event(start(), 1000 - char, wordLength = 12).state
        val r = BackspaceSlideMode.next(1000 - char * 3, after, words)

        assertEquals(0, r.steps)
        assertEquals("direction must be unchanged", -1, r.state.direction)
    }

    // ---------------------------------------------------------------- reversing in word mode

    @Test
    fun `reversing in word mode takes a word back the other way`() {
        val after = event(start(), 1000 - char, wordLength = 6).state
        val back = BackspaceSlideMode.next(after.anchorX + char, after, words)

        assertEquals(1, back.steps)
        assertEquals(1, back.state.direction)
    }

    @Test
    fun `reversing stays in word mode`() {
        // Granularity is set by the entry gesture. Nothing during the slide changes it.
        val after = event(start(), 1000 - char, wordLength = 6).state
        val back = event(after, after.anchorX + char, wordLength = 6)
        val onward = BackspaceSlideMode.next(back.state.anchorX + char * 7, back.state, words)

        assertEquals("still stepping by words", 1, onward.steps)
    }

    @Test
    fun `jitter below a character is not a reversal`() {
        val after = event(start(), 1000 - char, wordLength = 6).state
        val r = BackspaceSlideMode.next(after.anchorX + (char - 1), after, words)

        assertEquals(0, r.steps)
        assertEquals(-1, r.state.direction)
    }

    @Test
    fun `a rightward slide works the same way`() {
        val r = BackspaceSlideMode.next(1000 + char, start(), words)
        assertEquals(1, r.steps)
        assertEquals(1, r.state.direction)
    }
}
