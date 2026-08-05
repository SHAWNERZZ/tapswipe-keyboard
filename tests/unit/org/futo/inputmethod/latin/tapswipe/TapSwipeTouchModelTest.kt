package org.futo.inputmethod.latin.tapswipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * JVM tests for the learned touch model.
 *
 * The interesting cases are the safety gates. A model that learns is easy; a model that refuses to
 * act on thin or contradictory evidence is the part that keeps this feature from typing words the
 * user never aimed at.
 */
class TapSwipeTouchModelTest {

    private val layout = "P:qwerty"
    private val halfW = 0.05f
    private val halfH = 0.08f
    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() = TapSwipeTouchModel.reset()

    private fun feed(cp: Int, dx: Float, dy: Float, n: Int, at: Long = now) {
        repeat(n) { TapSwipeTouchModel.record(layout, cp, dx, dy, 1f, at) }
    }

    private fun shift(cp: Int, at: Long = now) =
        TapSwipeTouchModel.shiftFor(layout, cp, halfW, halfH, at)

    // ---------------------------------------------------------------- learning

    @Test
    fun `an unseen key has no shift`() {
        assertNull(shift('q'.code))
    }

    @Test
    fun `consistent evidence produces a shift in the same direction`() {
        feed('e'.code, 0.01f, -0.02f, 30)

        val s = shift('e'.code)
        assertNotNull(s)
        assertTrue("expected +dx, got ${s!![0]}", s[0] > 0f)
        assertTrue("expected -dy, got ${s[1]}", s[1] < 0f)
    }

    /**
     * A single sample must barely move anything. The alternative - acting immediately - means one
     * fat-fingered swipe visibly relocates a key.
     */
    @Test
    fun `one sample is damped far below its measured value`() {
        feed('e'.code, 0.02f, 0f, 1)

        val s = shift('e'.code)
        if (s != null) {
            assertTrue("one sample moved the key by ${s[0]}", abs(s[0]) < 0.02f * 0.25f)
        }
    }

    @Test
    fun `more evidence produces a larger applied shift`() {
        feed('e'.code, 0.01f, 0f, 2)
        val small = shift('e'.code)?.get(0) ?: 0f

        feed('e'.code, 0.01f, 0f, 40)
        val large = shift('e'.code)?.get(0) ?: 0f

        assertTrue("expected confidence to grow: $small -> $large", large > small)
    }

    // ---------------------------------------------------------------- safety gates

    @Test
    fun `the shift is capped at a fraction of key size`() {
        // Wildly beyond the cap, and consistent, so only the cap can stop it.
        feed('e'.code, 5.0f, 5.0f, 200)

        val s = shift('e'.code)
        assertNotNull(s)
        val capX = halfW * 2f * TapSwipeTouchModel.MAX_SHIFT_FRACTION
        val capY = halfH * 2f * TapSwipeTouchModel.MAX_SHIFT_FRACTION
        assertTrue("x exceeded cap: ${s!![0]} > $capX", abs(s[0]) <= capX + 1e-5f)
        assertTrue("y exceeded cap: ${s[1]} > $capY", abs(s[1]) <= capY + 1e-5f)
    }

    /**
     * Scattered evidence averages to a confident-looking number with nothing behind it. Someone who
     * hits a key all over has no habit to learn, and pretending otherwise is worse than doing
     * nothing.
     */
    @Test
    fun `scattered evidence is suppressed even when plentiful`() {
        repeat(100) { i ->
            val swing = if (i % 2 == 0) 0.5f else -0.48f
            TapSwipeTouchModel.record(layout, 'e'.code, swing, 0f, 1f, now)
        }

        val s = shift('e'.code)
        if (s != null) {
            assertTrue("scatter should have suppressed the shift, got ${s[0]}", abs(s[0]) < 0.004f)
        }
    }

    @Test
    fun `evidence fades with wall-clock time`() {
        feed('e'.code, 0.02f, 0f, 30)
        val fresh = shift('e'.code)?.get(0) ?: 0f

        // Same evidence, read a year later. Decay is on elapsed time, not on update count, so an
        // untouched key fades on its own rather than holding its bias forever.
        val stale = shift('e'.code, now + 365 * day)?.get(0) ?: 0f

        assertTrue("expected decay: $fresh -> $stale", abs(stale) < abs(fresh))
    }

    @Test
    fun `reset clears everything`() {
        feed('e'.code, 0.02f, 0f, 30)
        assertNotNull(shift('e'.code))

        TapSwipeTouchModel.reset()

        assertNull(shift('e'.code))
        assertEquals(0, TapSwipeTouchModel.totalSamples())
    }

    // ---------------------------------------------------------------- bookkeeping

    @Test
    fun `layouts are kept separate`() {
        feed('e'.code, 0.02f, 0f, 30)

        assertNull(TapSwipeTouchModel.shiftFor("P:dvorak", 'e'.code, halfW, halfH, now))
        assertNotNull(shift('e'.code))
    }

    @Test
    fun `orientation is part of the layout identity`() {
        val portrait = TapSwipeTouchModel.layoutKey("qwerty", isLandscape = false)
        val landscape = TapSwipeTouchModel.layoutKey("qwerty", isLandscape = true)
        assertTrue(portrait != landscape)
    }

    @Test
    fun `stats report both the measurement and what was actually applied`() {
        feed('e'.code, 5.0f, 0f, 200)

        val stats = TapSwipeTouchModel.statsFor(layout, halfW, halfH, now)
        val e = stats.firstOrNull { it.codePoint == 'e'.code }
        assertNotNull(e)
        // The gap between the two is the cap doing its job; showing only one would hide it.
        assertTrue("raw mean should be large, was ${e!!.meanDx}", e.meanDx > 1f)
        assertTrue("applied should be capped, was ${e.appliedDx}", e.appliedDx < 0.05f)
        assertEquals(200, e.count)
    }

    @Test
    fun `non-finite samples are ignored`() {
        TapSwipeTouchModel.record(layout, 'e'.code, Float.NaN, 0f, 1f, now)
        TapSwipeTouchModel.record(layout, 'e'.code, 0f, Float.POSITIVE_INFINITY, 1f, now)

        assertEquals(0, TapSwipeTouchModel.totalSamples())
    }

    @Test
    fun `zero and negative weights are ignored`() {
        TapSwipeTouchModel.record(layout, 'e'.code, 0.02f, 0f, 0f, now)
        TapSwipeTouchModel.record(layout, 'e'.code, 0.02f, 0f, -1f, now)

        assertEquals(0, TapSwipeTouchModel.totalSamples())
    }
}
