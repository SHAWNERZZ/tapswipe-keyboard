package org.futo.inputmethod.keyboard.internal

import kotlin.math.abs

/**
 * Whether a touch on the backspace key has become an upward swipe.
 *
 * The gesture deletes the last word in one motion. It shares the key with a horizontal slide that
 * selects text as it moves, and those two must never be confused. A slide that drifts upward and
 * fires a word delete would remove text the user was in the middle of selecting.
 *
 * Two tests separate them. The motion must travel far enough to be deliberate, and it must be more
 * vertical than horizontal. A slide is almost entirely horizontal, so the second test alone rules
 * out most of the overlap. The caller adds the third guard, which is a lockout once the slide has
 * committed a step.
 *
 * Pure, so the thresholds can be tuned and checked without a device. Those thresholds are the part
 * of this feature most likely to need adjusting after real use.
 */
object BackspaceSwipeUp {

    /**
     * How far the finger must travel upward, as a fraction of key height.
     *
     * Matches the comma pull's threshold. Both gestures leave a key in a straight line, and one
     * convention is easier to reason about than two numbers that drift apart.
     */
    private const val MIN_UP_FRACTION = 0.5f

    /**
     * @param dx horizontal travel from the touch-down point, positive to the right
     * @param dy vertical travel from the touch-down point, positive downward
     * @param keyHeight height of the key the touch started on
     */
    @JvmStatic
    fun isTriggered(dx: Int, dy: Int, keyHeight: Int): Boolean {
        if (keyHeight <= 0) return false

        // Upward only. A downward swipe on backspace means nothing yet, and treating it as this
        // gesture would fire on a finger sliding off the bottom of the keyboard.
        val up = -dy
        if (up < keyHeight * MIN_UP_FRACTION) return false

        // Steeper than 45 degrees. This is what keeps a long horizontal slide from qualifying, no
        // matter how much it wanders.
        return up > abs(dx)
    }
}
