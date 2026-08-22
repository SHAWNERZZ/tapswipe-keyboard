package org.futo.inputmethod.keyboard.internal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for what gets drawn for the word being typed.
 *
 * Two rules matter here. Publishing replaces the whole list, so the drawing cannot drift out of
 * step with the strokes it came from. And a stroke is drawn as a tap or a path by its shape, not by
 * the label the session gave it.
 */
class WordGestureTrailTest {

    private val keyWidth = 100f

    @Before
    fun setUp() {
        WordGestureTrail.onChanged = null
        WordGestureTrail.clear()
    }

    @After
    fun tearDown() {
        WordGestureTrail.onChanged = null
        WordGestureTrail.clear()
    }

    private fun gesture(vararg pts: Pair<Float, Float>) = WordGestureTrail.Gesture(
        false, pts.map { it.first }.toFloatArray(), pts.map { it.second }.toFloatArray())

    // ---------------------------------------------------------------- publishing

    @Test
    fun `a new word has nothing to draw`() {
        assertTrue(WordGestureTrail.isEmpty())
    }

    @Test
    fun `publishing replaces everything rather than appending`() {
        WordGestureTrail.publish(listOf(gesture(0f to 0f), gesture(1f to 1f)))
        WordGestureTrail.publish(listOf(gesture(2f to 2f)))

        val g = WordGestureTrail.snapshot()
        assertEquals("the second publish must not have been added to the first", 1, g.size)
        assertEquals(2f, g[0].xs[0], 0f)
    }

    /**
     * The reason this is a projection. A backspace removes a stroke and republishes what is left,
     * so the drawing cannot disagree with the session about order or count.
     */
    @Test
    fun `republishing a shorter list is how a stroke is removed`() {
        val a = gesture(0f to 0f)
        val b = gesture(1f to 1f)
        WordGestureTrail.publish(listOf(a, b))
        WordGestureTrail.publish(listOf(a))

        assertEquals(1, WordGestureTrail.snapshot().size)
    }

    @Test
    fun `clearing empties the drawing`() {
        WordGestureTrail.publish(listOf(gesture(0f to 0f)))
        WordGestureTrail.clear()
        assertTrue(WordGestureTrail.isEmpty())
    }

    @Test
    fun `the view is told when the drawing changes`() {
        var calls = 0
        WordGestureTrail.onChanged = Runnable { calls++ }

        WordGestureTrail.publish(listOf(gesture(0f to 0f)))
        WordGestureTrail.publish(listOf(gesture(1f to 1f)))
        WordGestureTrail.clear()

        assertEquals(3, calls)
    }

    /** Clearing an already empty drawing changes nothing, so it must not cause a repaint. */
    @Test
    fun `clearing when empty does not notify`() {
        var calls = 0
        WordGestureTrail.onChanged = Runnable { calls++ }
        WordGestureTrail.clear()
        assertEquals(0, calls)
    }

    // ---------------------------------------------------------------- tap or path

    @Test
    fun `a single point is a tap`() {
        assertTrue(WordGestureTrail.looksLikeTap(floatArrayOf(5f), floatArrayOf(5f), keyWidth))
    }

    /**
     * The case this rule exists for. A tap made while another finger is swiping reaches the session
     * through the swipe path and is labelled a swipe. It travelled almost nowhere, so it is drawn
     * as what the finger actually did.
     */
    @Test
    fun `a stroke that stayed inside one key is a tap`() {
        val xs = floatArrayOf(50f, 54f, 58f, 55f)
        val ys = floatArrayOf(50f, 52f, 49f, 51f)
        assertTrue(WordGestureTrail.looksLikeTap(xs, ys, keyWidth))
    }

    @Test
    fun `a stroke that crossed a key width is a swipe`() {
        val xs = floatArrayOf(0f, 60f, 130f)
        val ys = floatArrayOf(0f, 5f, 8f)
        assertFalse(WordGestureTrail.looksLikeTap(xs, ys, keyWidth))
    }

    /** Travel in either direction counts, so a vertical swipe is still a swipe. */
    @Test
    fun `a vertical stroke is measured too`() {
        val xs = floatArrayOf(10f, 12f, 11f)
        val ys = floatArrayOf(0f, 70f, 140f)
        assertFalse(WordGestureTrail.looksLikeTap(xs, ys, keyWidth))
    }

    @Test
    fun `the boundary is one key width`() {
        val justUnder = floatArrayOf(0f, keyWidth - 1f)
        val justOver = floatArrayOf(0f, keyWidth + 1f)
        val flat = floatArrayOf(0f, 0f)
        assertTrue(WordGestureTrail.looksLikeTap(justUnder, flat, keyWidth))
        assertFalse(WordGestureTrail.looksLikeTap(justOver, flat, keyWidth))
    }

    /** With no layout applied there is no threshold, so shape cannot be judged. */
    @Test
    fun `an unknown key width leaves a multi-point stroke as a path`() {
        val xs = floatArrayOf(0f, 2f)
        val ys = floatArrayOf(0f, 2f)
        assertFalse(WordGestureTrail.looksLikeTap(xs, ys, 0f))
        assertTrue("a single point is still a tap",
            WordGestureTrail.looksLikeTap(floatArrayOf(1f), floatArrayOf(1f), 0f))
    }

    // ---------------------------------------------------------------- brightness

    @Test
    fun `the newest stroke is fully bright`() {
        assertEquals(255, WordGestureTrail.alphaFor(0))
    }

    @Test
    fun `each older stroke is dimmer`() {
        assertTrue(WordGestureTrail.alphaFor(0) > WordGestureTrail.alphaFor(1))
        assertTrue(WordGestureTrail.alphaFor(1) > WordGestureTrail.alphaFor(2))
    }

    /**
     * Old strokes are drawn over the keys, and Master Mode reduces those to dots, so they must not
     * compete with what is underneath.
     */
    @Test
    fun `old strokes settle at a dim floor rather than vanishing`() {
        val far = WordGestureTrail.alphaFor(20)
        assertTrue("must stay visible", far > 0)
        assertTrue("must stay out of the way", far < 100)
        assertEquals("and must not keep falling", far, WordGestureTrail.alphaFor(40))
    }

    @Test
    fun `alpha is always drawable`() {
        for (i in 0..50) {
            assertTrue("out of range at $i", WordGestureTrail.alphaFor(i) in 0..255)
        }
    }
}
