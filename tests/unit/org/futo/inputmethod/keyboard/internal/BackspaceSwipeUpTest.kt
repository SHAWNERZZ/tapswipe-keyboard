package org.futo.inputmethod.keyboard.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the upward swipe on the delete key.
 *
 * The gesture shares its key with a horizontal slide that selects text. Most of these cases assert
 * that the gesture does not fire, because a false positive deletes a word out of a selection the
 * user was still making. A missed swipe costs one more try.
 *
 * Key height is 140 throughout, so the threshold sits at 70.
 */
class BackspaceSwipeUpTest {

    private val keyH = 140

    /** Screen coordinates put positive y downward, so an upward swipe has negative dy. */
    private fun up(distance: Int, sideways: Int = 0) =
        BackspaceSwipeUp.isTriggered(sideways, -distance, keyH)

    // ---------------------------------------------------------------- what it accepts

    @Test
    fun `a straight swipe up past the threshold fires`() {
        assertTrue(up(keyH))
    }

    @Test
    fun `a swipe with some sideways drift still fires`() {
        // A thumb does not travel straight. Anything steeper than 45 degrees counts.
        assertTrue(up(100, sideways = 40))
        assertTrue(up(100, sideways = -40))
    }

    // ---------------------------------------------------------------- what it refuses

    @Test
    fun `a short flick does not fire`() {
        assertFalse(up(keyH / 4))
    }

    @Test
    fun `movement just under the threshold does not fire`() {
        assertFalse(up(69))
        assertTrue(up(70))
    }

    /** The case that matters most. A slide is nearly all horizontal and must never qualify. */
    @Test
    fun `a horizontal slide does not fire`() {
        assertFalse(BackspaceSwipeUp.isTriggered(-300, 0, keyH))
        assertFalse(BackspaceSwipeUp.isTriggered(300, 0, keyH))
    }

    @Test
    fun `a long slide that drifts upward does not fire`() {
        // Well past the vertical threshold, and still mostly sideways.
        assertFalse(up(80, sideways = -300))
    }

    @Test
    fun `a diagonal at exactly 45 degrees does not fire`() {
        assertFalse(up(100, sideways = 100))
    }

    @Test
    fun `a downward swipe does not fire`() {
        assertFalse(BackspaceSwipeUp.isTriggered(0, keyH, keyH))
    }

    @Test
    fun `no movement does not fire`() {
        assertFalse(BackspaceSwipeUp.isTriggered(0, 0, keyH))
    }

    // ---------------------------------------------------------------- degenerate input

    @Test
    fun `a key with no height cannot fire`() {
        assertFalse(BackspaceSwipeUp.isTriggered(0, -500, 0))
    }

    @Test
    fun `the threshold scales with the key`() {
        // The same travel that fires on a short key must not fire on a tall one.
        assertTrue(BackspaceSwipeUp.isTriggered(0, -60, 100))
        assertFalse(BackspaceSwipeUp.isTriggered(0, -60, 200))
    }
}
