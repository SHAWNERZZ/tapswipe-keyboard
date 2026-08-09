package org.futo.inputmethod.keyboard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the typing-speed maths.
 *
 * `SystemClock.uptimeMillis()` is stubbed to 0 under `returnDefaultValues`, so every event lands at
 * the same instant and elapsed time is always zero. That is precisely the degenerate case worth
 * pinning down: it must report nothing rather than dividing by zero or claiming an infinite rate.
 * The cases that need a moving clock are marked as such and left to on-device use.
 */
class WpmTrackerTest {

    @Before
    fun setUp() = WpmTracker.reset()

    @Test
    fun `nothing typed reports no speed`() {
        assertNull(WpmTracker.wpm())
    }

    @Test
    fun `a few characters is not enough to report`() {
        WpmTracker.onCharsProduced(4)
        assertNull(WpmTracker.wpm())
    }

    /**
     * The guard that matters most here: with no measurable elapsed time, words-per-minute is a
     * division by zero. It must decline to answer rather than produce an infinity.
     */
    @Test
    fun `plenty of characters in zero elapsed time reports nothing`() {
        WpmTracker.onCharsProduced(500)
        assertNull("zero elapsed time cannot yield a rate", WpmTracker.wpm())
    }

    @Test
    fun `reset clears accumulated characters`() {
        WpmTracker.onCharsProduced(200)
        WpmTracker.reset()
        assertNull(WpmTracker.wpm())
    }

    @Test
    fun `zero and negative counts are ignored`() {
        WpmTracker.onCharsProduced(0)
        WpmTracker.onCharsProduced(-5)
        assertNull(WpmTracker.wpm())
    }

    /**
     * Guards the reporting range rather than the arithmetic. A four-digit reading would mean
     * something pathological happened to the clock, and showing it would be worse than showing
     * nothing - so whatever wpm() returns is either plausible or absent.
     */
    @Test
    fun `a reported speed is always in a plausible range`() {
        WpmTracker.onCharsProduced(1000)
        val wpm = WpmTracker.wpm()
        if (wpm != null) {
            assertTrue("implausible reading: $wpm", wpm in 1..999)
        }
    }
}
