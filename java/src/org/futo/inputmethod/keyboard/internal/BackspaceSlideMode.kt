package org.futo.inputmethod.keyboard.internal

import kotlin.math.abs

/**
 * How far a delete-slide has moved, and at what granularity.
 *
 * Distance is denominated in characters whatever granularity is active. A word costs the slide
 * exactly what its letters would have cost - so sweeping over "the quick brown fox" takes the same
 * travel whether it is deleted word by word or letter by letter. Only the *unit of selection*
 * changes; the exchange rate between finger travel and text consumed never does.
 *
 * That is the point, and the reason a flat per-word step was wrong. A fixed word step is shorter
 * than the letters it removes, so word mode consumed text faster than the finger was moving: a
 * slide meant to trim a few characters swallowed whole words, and winning them back meant sliding
 * the other way further than the screen allows.
 *
 * ### Debt, not distance
 *
 * A word's cost is its length, which is unknown until it has been consumed. So a word is taken as
 * soon as the finger moves, and the slide is then charged for it via [chargeWord] - the next word
 * waits until that debt has been walked off.
 *
 * The debt is tracked separately from the anchor rather than by pushing the anchor forward, because
 * an anchor moved past the finger reads as travel in the opposite direction, which is
 * indistinguishable from the user reversing. Keeping the two apart is what lets a long word be
 * expensive without looking like a bounce.
 *
 * ### Reversing
 *
 * Reversing past a character drops the gesture to character precision for the rest of the gesture,
 * as the original Nintype's slide did: sweep back over a few words, bounce, and pick off the last
 * few letters exactly. One-way, because a second bounce is another nudge in the same spirit as the
 * first, not a request to go back to deleting whole words.
 *
 * Pure, with the caller holding [State] between events, so every rule here is checked without a
 * device.
 */
object BackspaceSlideMode {

    /**
     * @param anchorX the point up to which travel has been paid for
     * @param extremeX the furthest the finger has reached along [direction]
     * @param debtPx travel still owed before another word may be taken
     * @param direction which way this slide is going: -1, 1, or 0 before it has committed
     * @param latchedFine whether a reversal has pinned this gesture to characters
     */
    data class State(
        val anchorX: Int,
        val extremeX: Int = anchorX,
        val debtPx: Int = 0,
        val direction: Int = 0,
        val latchedFine: Boolean = false
    )

    /**
     * @param charStepPx travel that consumes one character - the unit for everything
     * @param wordsAllowed false when the user asked for character deletion and nothing else
     */
    data class Config(val charStepPx: Int, val wordsAllowed: Boolean)

    /**
     * @param steps characters to consume, or in word mode the direction to take one word in
     * @param wordMode whether [steps] means a word rather than a count of characters
     * @param state carry to the next event; in word mode it is not yet charged for the word
     */
    data class Result(val steps: Int, val wordMode: Boolean, val state: State)

    @JvmStatic
    fun next(x: Int, state: State, config: Config): Result {
        if (state.latchedFine || !config.wordsAllowed) return fine(x, state, config)

        val travel = x - state.anchorX

        // Nothing committed yet, so there is no direction to continue or reverse. The first
        // character of travel establishes one and takes the first word with it.
        if (state.direction == 0) {
            if (abs(travel) < config.charStepPx) return Result(0, true, state)
            val direction = if (travel > 0) 1 else -1
            return Result(direction, true,
                state.copy(anchorX = x, extremeX = x, direction = direction))
        }

        // Reversal is measured from the furthest point reached, never from the anchor.
        //
        // The finger runs ahead of the anchor by however much of the current word is still unpaid -
        // up to a whole word's length. Measuring a bounce from the anchor therefore means retracing
        // all of that before the turn registers at all, and the backspace key sits at the edge of
        // the keyboard, so there is barely any room to spend on a dead zone. Turning back one
        // character from wherever the finger got to is what the gesture actually means.
        val backtrack = (x - state.extremeX) * state.direction
        if (backtrack <= -config.charStepPx) {
            // Re-anchored at the turnaround so un-selecting starts immediately and proceeds one
            // character per step, rather than first working back to a point already passed.
            return fine(x, state.copy(latchedFine = true, anchorX = state.extremeX), config)
        }

        val extremeX = if ((x - state.extremeX) * state.direction > 0) x else state.extremeX

        // How far past the paid-up point the finger is, measured along the way it was going.
        val progress = travel * state.direction
        if (progress < state.debtPx) return Result(0, true, state.copy(extremeX = extremeX))

        // The debt is walked off: the anchor moves by what was owed, not to where the finger is,
        // so travel beyond it counts toward the next word rather than being forgiven.
        return Result(
            steps = state.direction,
            wordMode = true,
            state = state.copy(
                anchorX = state.anchorX + state.direction * state.debtPx,
                extremeX = extremeX,
                debtPx = 0
            )
        )
    }

    /** Character stepping, for a gesture that is fine either by setting or by having reversed. */
    private fun fine(x: Int, state: State, config: Config): Result {
        val steps = (x - state.anchorX) / config.charStepPx
        if (steps == 0) return Result(0, false, state)
        return Result(
            steps = steps,
            wordMode = false,
            state = state.copy(anchorX = state.anchorX + steps * config.charStepPx)
        )
    }

    /**
     * Charges the slide for a word that turned out to be [chars] characters long.
     *
     * Called after the word has been consumed, since its length is only known then. [chars] is what
     * the editor actually moved over, so the spaces and punctuation between words are paid for at
     * the same rate as letters - which is what keeps travel and text in step.
     */
    @JvmStatic
    fun chargeWord(state: State, chars: Int, config: Config): State {
        // Never free: a word the editor reported as costing nothing would let an almost stationary
        // finger consume the document one event at a time.
        val cost = maxOf(1, abs(chars))
        return state.copy(debtPx = cost * config.charStepPx)
    }
}
