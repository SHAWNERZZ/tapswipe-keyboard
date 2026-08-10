package org.futo.inputmethod.latin.tapswipe

import org.futo.inputmethod.latin.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for whole-stroke shortcut matching.
 *
 * These matter more than most: a false positive replaces a word the user meant to type with
 * punctuation, and it happens at the end of a gesture when they are already moving on. Nearly every
 * case here is therefore a *rejection* - the shapes that must be handed back to the decoder
 * untouched.
 *
 * Only the geometry is covered. Deciding which key a point landed on needs a real Keyboard and the
 * native proximity code behind it, so that half is verified on a device.
 */
class NintypeGesturesTest {

    private val keyW = 100
    private val keyH = 140

    /** A straight vertical pull, [steps] points from (x, y) down by [dy]. */
    private fun straightDown(x: Int, y: Int, dy: Int, steps: Int = 8): Pair<IntArray, IntArray> {
        val xs = IntArray(steps) { x }
        val ys = IntArray(steps) { y + dy * it / (steps - 1) }
        return xs to ys
    }

    /** V sits centred at x=300 in these tests, matching the strokes built below. */
    private val anchorCentreX = 300

    private fun match(
        xs: IntArray, ys: IntArray,
        startCode: Int = 'v'.code,
        endCode: Int = Constants.CODE_SPACE
    ) = NintypeGestures.matchShape(
        startCode, endCode, xs, ys, xs.size, anchorCentreX, keyW, keyW, keyH)

    // ---------------------------------------------------------------- the shape it accepts

    @Test
    fun `a straight pull down from V to the space bar is a comma`() {
        val (xs, ys) = straightDown(300, 400, keyH)
        assertEquals(NintypeGestures.Shortcut.COMMA, match(xs, ys))
    }

    @Test
    fun `a slightly wobbly pull is still a comma`() {
        // Nobody draws a perfect line with a thumb; a little sway must not disqualify it.
        val (xs, ys) = straightDown(300, 400, keyH, steps = 9)
        val wobbly = IntArray(xs.size) { xs[it] + (if (it % 2 == 0) 12 else -12) }
        assertEquals(NintypeGestures.Shortcut.COMMA, match(wobbly, ys))
    }

    // ---------------------------------------------------------------- what it must refuse

    /**
     * Superseded by the start-band cases below: C is now an accepted starting key *if the touch
     * landed near V*, so what disqualifies a stroke is distance from V rather than which key
     * reported it. Kept as the far-away version of that check.
     */
    @Test
    fun `a stroke starting well away from V is not a comma`() {
        val (xs, ys) = straightDown(anchorCentreX - keyW * 3, 400, keyH)
        assertNull(match(xs, ys, startCode = 'x'.code))
    }

    @Test
    fun `a stroke not ending on the space bar is not a comma`() {
        val (xs, ys) = straightDown(300, 400, keyH)
        assertNull(match(xs, ys, endCode = 'b'.code))
    }

    @Test
    fun `a short slip downward is not a comma`() {
        // Barely leaving the key is a mistyped tap, not a deliberate pull.
        val (xs, ys) = straightDown(300, 400, keyH / 4)
        assertNull(match(xs, ys))
    }

    @Test
    fun `an upward stroke is not a comma`() {
        val (xs, ys) = straightDown(300, 400, -keyH)
        assertNull(match(xs, ys))
    }

    /**
     * The important rejection. A word swipe that happens to start on V and finish low - "very",
     * say, sweeping right - must stay a word. Shallower than 45 degrees disqualifies it.
     */
    @Test
    fun `a diagonal sweep across the keyboard is not a comma`() {
        val steps = 10
        val xs = IntArray(steps) { 300 + 400 * it / (steps - 1) }
        val ys = IntArray(steps) { 400 + keyH * it / (steps - 1) }
        assertNull(match(xs, ys))
    }

    /**
     * Steep enough overall, but it wandered a long way and came back. A word swipe can cross the
     * space bar on its way somewhere; it does not travel straight down it.
     */
    @Test
    fun `a stroke that detours sideways and returns is not a comma`() {
        val xs = intArrayOf(300, 340, 480, 520, 420, 320, 300, 300)
        val ys = intArrayOf(400, 420, 450, 490, 520, 540, 545, 400 + keyH)
        assertNull(match(xs, ys))
    }

    // ---------------------------------------------------------------- degenerate input

    @Test
    fun `too few points is not a comma`() {
        assertNull(match(intArrayOf(300, 300), intArrayOf(400, 400 + keyH)))
    }

    @Test
    fun `zero key size cannot match`() {
        val (xs, ys) = straightDown(300, 400, keyH)
        assertNull(NintypeGestures.matchShape(
            'v'.code, Constants.CODE_SPACE, xs, ys, xs.size, anchorCentreX, keyW, 0, 0))
    }

    // ---------------------------------------------------------------- the start band

    /**
     * Reaching V with the right hand lands a little toward B, and the finger is already moving
     * before it settles. Half a key either side of V's own edges is accepted so those strokes are
     * not thrown away.
     */
    @Test
    fun `starting half a key toward B is still a comma`() {
        val (xs, ys) = straightDown(anchorCentreX + keyW, 400, keyH)
        assertEquals(NintypeGestures.Shortcut.COMMA, match(xs, ys, startCode = 'b'.code))
    }

    @Test
    fun `starting half a key toward C is still a comma`() {
        val (xs, ys) = straightDown(anchorCentreX - keyW, 400, keyH)
        assertEquals(NintypeGestures.Shortcut.COMMA, match(xs, ys, startCode = 'c'.code))
    }

    /** A whole key away is B proper, not a near miss on V, and must stay a normal stroke. */
    @Test
    fun `starting a full key beyond V is not a comma`() {
        val (xs, ys) = straightDown(anchorCentreX + keyW * 2, 400, keyH)
        assertNull(match(xs, ys, startCode = 'b'.code))
    }

    @Test
    fun `a key that is not adjacent to V cannot start the pull`() {
        val (xs, ys) = straightDown(anchorCentreX, 400, keyH)
        assertNull(match(xs, ys, startCode = 'g'.code))
    }
}
