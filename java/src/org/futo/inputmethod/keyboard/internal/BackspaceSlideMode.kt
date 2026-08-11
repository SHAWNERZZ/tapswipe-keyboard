package org.futo.inputmethod.keyboard.internal

/**
 * Granularity for a slide-to-delete gesture, and when it switches mid-slide.
 *
 * The slide starts at whichever granularity the backspace setting names ("base"), one step per
 * [BASE_STEP]/[OTHER_STEP] pixels of travel. Reversing direction - moving back the way you came,
 * past a full step's worth - switches for the rest of this gesture to the other granularity, so a
 * word-swipe can bounce back a little and continue at character precision, matching what the
 * original Nintype's slide-to-delete did.
 *
 * The switch is a one-way latch, not a toggle: once a gesture has switched, it stays switched even
 * if the finger reverses again. Reasoned from what the gesture is for - the second reversal is
 * "I want to nudge back one more character," the same motion as the first, not a request to jump
 * back to coarse deletion. A toggle would make the third bounce in a row silently mean something
 * different from the first.
 *
 * Kept as a pure function over anchor/latch/sign rather than something stateful, so the decision
 * that actually matters - did this movement cross a step, did it reverse - is checked without a
 * device. [org.futo.inputmethod.keyboard.PointerTracker] owns the mutable state between calls, the
 * same way it already owns the anchor position for the cursor-slide gesture beside this one.
 */
object BackspaceSlideMode {

    /**
     * @param steps how many granularity-steps to apply; zero means nothing crossed a threshold yet
     * @param newAnchorX where the next call's [x] should be measured from
     * @param latchedToOther whether this gesture has switched away from the base granularity
     * @param lastStepSign the sign of the most recent nonzero [steps], carried forward so the next
     *   call can tell whether a future step reverses it. Unchanged from the input when steps is
     *   zero - direction is only known once something has actually moved.
     */
    data class Result(
        val steps: Int,
        val newAnchorX: Int,
        val latchedToOther: Boolean,
        val lastStepSign: Int
    )

    /**
     * @param x current touch position
     * @param anchorX position the last step was measured from
     * @param latchedToOther whether a reversal has already switched this gesture to [otherStepPx]
     * @param lastStepSign sign of the last committed step, or 0 before the first one
     * @param baseStepPx pixels per step at the gesture's starting granularity
     * @param otherStepPx pixels per step at the granularity a reversal switches to
     */
    @JvmStatic
    fun next(
        x: Int,
        anchorX: Int,
        latchedToOther: Boolean,
        lastStepSign: Int,
        baseStepPx: Int,
        otherStepPx: Int
    ): Result {
        var latched = latchedToOther
        var steps = (x - anchorX) / (if (latched) otherStepPx else baseStepPx)

        // Only base granularity can reverse into other: once switched, this gesture stays switched
        // regardless of which way the finger moves next (see the class doc on why this is a latch).
        // A step of 0 has no direction to compare, and lastStepSign of 0 means nothing has committed
        // yet - the very first step of a gesture is never a "reversal" of anything.
        if (!latched && steps != 0 && lastStepSign != 0) {
            val sign = if (steps > 0) 1 else -1
            if (sign != lastStepSign) {
                // Re-measured at the finer granularity from the same anchor, rather than carrying
                // over the base-granularity step count, so the crossing itself is felt at whatever
                // precision the gesture just switched to - not in whatever units it was measured in
                // a moment ago.
                latched = true
                steps = (x - anchorX) / otherStepPx
            }
        }

        if (steps == 0) return Result(0, anchorX, latched, lastStepSign)

        val stepPx = if (latched) otherStepPx else baseStepPx
        return Result(steps, anchorX + steps * stepPx, latched, if (steps > 0) 1 else -1)
    }
}
