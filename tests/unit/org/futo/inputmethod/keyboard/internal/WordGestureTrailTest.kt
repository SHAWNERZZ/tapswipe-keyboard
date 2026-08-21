package org.futo.inputmethod.keyboard.internal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the word-level gesture store and its brightness grading.
 *
 * The grading is the part worth pinning down. It has to answer "how many backspace presses away is
 * this gesture", and it has to keep the oldest gestures dim enough to stay behind the key labels.
 */
class WordGestureTrailTest {

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

    // ---------------------------------------------------------------- the store

    @Test
    fun `a new word has nothing to draw`() {
        assertTrue(WordGestureTrail.isEmpty())
        assertEquals(0, WordGestureTrail.snapshot().size)
    }

    @Test
    fun `taps and swipes are kept in the order they were made`() {
        WordGestureTrail.addTap(1f, 1f)
        WordGestureTrail.addSwipe(floatArrayOf(2f, 3f), floatArrayOf(2f, 3f), 2)
        WordGestureTrail.addTap(4f, 4f)

        val g = WordGestureTrail.snapshot()
        assertEquals(3, g.size)
        assertTrue(g[0].isTap)
        assertFalse(g[1].isTap)
        assertTrue(g[2].isTap)
    }

    /** The caller reuses its buffers between strokes, so the store has to take its own copy. */
    @Test
    fun `a swipe is copied, not referenced`() {
        val xs = floatArrayOf(1f, 2f, 3f)
        val ys = floatArrayOf(1f, 2f, 3f)
        WordGestureTrail.addSwipe(xs, ys, 3)

        xs[0] = 99f
        ys[0] = 99f

        assertEquals(1f, WordGestureTrail.snapshot()[0].xs[0], 0f)
    }

    @Test
    fun `only the valid part of a swipe buffer is kept`() {
        val xs = FloatArray(100) { it.toFloat() }
        WordGestureTrail.addSwipe(xs, xs, 4)
        assertEquals(4, WordGestureTrail.snapshot()[0].xs.size)
    }

    @Test
    fun `an empty swipe is ignored`() {
        WordGestureTrail.addSwipe(floatArrayOf(), floatArrayOf(), 0)
        assertTrue(WordGestureTrail.isEmpty())
    }

    @Test
    fun `removing the last gesture takes the newest one`() {
        WordGestureTrail.addTap(1f, 1f)
        WordGestureTrail.addTap(2f, 2f)
        WordGestureTrail.removeLast()

        val g = WordGestureTrail.snapshot()
        assertEquals(1, g.size)
        assertEquals(1f, g[0].xs[0], 0f)
    }

    @Test
    fun `removing from an empty store is harmless`() {
        WordGestureTrail.removeLast()
        assertTrue(WordGestureTrail.isEmpty())
    }

    @Test
    fun `the store is bounded`() {
        repeat(500) { WordGestureTrail.addTap(it.toFloat(), 0f) }
        assertTrue("an unbounded list would be drawn every frame",
            WordGestureTrail.snapshot().size < 100)
    }

    /** Drawing iterates the copy, so a touch arriving mid-draw cannot disturb it. */
    @Test
    fun `a snapshot does not change when the store does`() {
        WordGestureTrail.addTap(1f, 1f)
        val snap = WordGestureTrail.snapshot()
        WordGestureTrail.addTap(2f, 2f)

        assertEquals(1, snap.size)
        assertEquals(2, WordGestureTrail.snapshot().size)
    }

    // ---------------------------------------------------------------- change notification

    @Test
    fun `the view is told when gestures arrive and leave`() {
        var calls = 0
        WordGestureTrail.onChanged = Runnable { calls++ }

        WordGestureTrail.addTap(1f, 1f)
        WordGestureTrail.removeLast()
        WordGestureTrail.addTap(2f, 2f)
        WordGestureTrail.clear()

        assertEquals(4, calls)
    }

    /** A clear that changes nothing must not cause a repaint. */
    @Test
    fun `clearing an empty store does not notify`() {
        var calls = 0
        WordGestureTrail.onChanged = Runnable { calls++ }

        WordGestureTrail.clear()

        assertEquals(0, calls)
    }

    // ---------------------------------------------------------------- brightness

    @Test
    fun `the newest gesture is fully bright`() {
        assertEquals(255, WordGestureTrail.alphaFor(0))
    }

    @Test
    fun `each older gesture is dimmer`() {
        val a = WordGestureTrail.alphaFor(0)
        val b = WordGestureTrail.alphaFor(1)
        val c = WordGestureTrail.alphaFor(2)
        assertTrue("brightness must fall with age", a > b && b > c)
    }

    /**
     * The floor matters. Trails are drawn over the keys, and Master Mode already reduces those to
     * dots, so an old gesture must not compete with the thing under it.
     */
    @Test
    fun `old gestures settle at a dim floor rather than vanishing`() {
        val far = WordGestureTrail.alphaFor(20)
        assertTrue("must stay visible", far > 0)
        assertTrue("must stay out of the way", far < 100)
        assertEquals("and must not keep falling", far, WordGestureTrail.alphaFor(40))
    }

    @Test
    fun `alpha is always drawable`() {
        for (i in 0..50) {
            val a = WordGestureTrail.alphaFor(i)
            assertTrue("alpha $a out of range at $i", a in 0..255)
        }
    }
}
