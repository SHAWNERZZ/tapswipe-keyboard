package org.futo.inputmethod.keyboard.internal

import kotlin.math.abs

/**
 * How far a delete-slide has moved, and how much text that removes.
 *
 * Granularity is decided before the slide starts and never changes during it. A plain slide deletes
 * characters. A slide that follows a tap on the same key deletes words. The entry gesture states
 * the intent, so nothing has to be inferred from the movement itself.
 *
 * That replaces an earlier design where the slide switched granularity when the finger reversed.
 * Switching mid-gesture meant the same movement could mean two things depending on history, and it
 * made the reachable text depend on where the finger had already been.
 *
 * ### The exchange rate
 *
 * Distance is denominated in characters in both modes. A word costs the slide what its letters
 * would have cost, so sweeping a phrase takes the same travel either way. Only the unit of
 * selection changes.
 *
 * A flat cost per word does not work. A fixed word step is shorter than the letters it removes, so
 * word mode would consume text faster than the finger moves. A slide meant to trim a few characters
 * would swallow whole words, and recovering them would need more travel than the screen has.
 *
 * ### Debt
 *
 * A word's length is known only after the editor has consumed it. So a word is taken as soon as the
 * finger moves, and the slide is charged afterwards through [chargeWord]. The next word waits until
 * that debt has been walked off. The slide therefore runs one word ahead of what it has paid for,
 * which is what makes the first word immediate.
 *
 * Debt is tracked apart from the anchor. Moving the anchor past the finger to carry a debt would
 * read as travel in the opposite direction, which is the same signal as reversing.
 *
 * Pure, with the caller holding [State] between events, so every rule here is checked without a
 * device.
 */
object BackspaceSlideMode {

    /**
     * @param anchorX the point up to which travel has been paid for
     * @param debtPx travel still owed before another word may be taken
     * @param direction which way this slide is going: -1, 1, or 0 before it has committed
     */
    data class State(
        val anchorX: Int,
        val debtPx: Int = 0,
        val direction: Int = 0
    )

    /**
     * @param charStepPx travel that consumes one character, the unit for both modes
     * @param wordMode true when a tap preceded this slide
     */
    data class Config(val charStepPx: Int, val wordMode: Boolean)

    /**
     * @param steps characters to consume, or in word mode the direction to take one word in
     * @param state carry to the next event; in word mode it is not yet charged for the word
     */
    data class Result(val steps: Int, val state: State)

    @JvmStatic
    fun next(x: Int, state: State, config: Config): Result {
        if (!config.wordMode) return characters(x, state, config)

        val travel = x - state.anchorX

        // Nothing committed yet, so there is no direction to continue. The first character of
        // travel establishes one and takes the first word with it.
        if (state.direction == 0) {
            if (abs(travel) < config.charStepPx) return Result(0, state)
            val direction = if (travel > 0) 1 else -1
            return Result(direction, State(anchorX = x, debtPx = 0, direction = direction))
        }

        val progress = travel * state.direction

        // Turned around. Take a word back the other way and carry on from here. Words are the unit
        // in both directions, so the slide stays symmetric.
        if (progress <= -config.charStepPx) {
            val direction = -state.direction
            return Result(direction, State(anchorX = x, debtPx = 0, direction = direction))
        }

        if (progress < state.debtPx) return Result(0, state)

        // The debt is walked off. The anchor moves by what was owed rather than to the finger, so
        // travel beyond it counts toward the next word instead of being forgiven.
        return Result(
            steps = state.direction,
            state = state.copy(
                anchorX = state.anchorX + state.direction * state.debtPx,
                debtPx = 0
            )
        )
    }

    private fun characters(x: Int, state: State, config: Config): Result {
        val steps = (x - state.anchorX) / config.charStepPx
        if (steps == 0) return Result(0, state)
        return Result(
            steps = steps,
            state = state.copy(anchorX = state.anchorX + steps * config.charStepPx)
        )
    }

    /**
     * Charges the slide for a word that turned out to be [chars] characters long.
     *
     * Called after the editor has consumed the word, because that is when its length is known.
     * [chars] is what the editor actually moved over, so the spaces and punctuation between words
     * are paid for at the same rate as letters.
     */
    @JvmStatic
    fun chargeWord(state: State, chars: Int, config: Config): State {
        // Never free. A word the editor reported as costing nothing would let an almost stationary
        // finger consume the document one event at a time.
        val cost = maxOf(1, abs(chars))
        return state.copy(debtPx = cost * config.charStepPx)
    }
}
