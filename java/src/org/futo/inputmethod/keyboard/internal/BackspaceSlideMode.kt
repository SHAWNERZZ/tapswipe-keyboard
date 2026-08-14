package org.futo.inputmethod.keyboard.internal

/**
 * How far a delete-slide has moved, and at what granularity it should be deleting.
 *
 * A slide-to-delete has two jobs that pull in opposite directions: trimming a few letters off the
 * end of a word, and throwing away a sentence. Word granularity is far too coarse for the first -
 * a whole word disappears before the finger has travelled a centimetre - and character granularity
 * is tediously slow for the second.
 *
 * So the gesture escalates rather than picking one. It always begins fine, because that is where
 * precision matters and where a wrong guess is most annoying; once it has travelled far enough to
 * be unambiguously a bulk deletion - [Config.escalateAfterPx], about two words' worth - it switches
 * to words. Reversing at any point drops it back to characters for the rest of the gesture, which
 * is how the original Nintype's slide behaved: sweep back over a few words, bounce, and pick off
 * the last few letters exactly.
 *
 * ### Why the switches latch
 *
 * Neither switch toggles. Escalation is one-way because a slide that has already covered two words
 * is not going to become a precision edit, and dropping back to characters mid-sweep would make a
 * long deletion crawl. The reversal is one-way for the reason it was one-way before: a second
 * bounce is another nudge in the same spirit as the first, not a request to go back to coarse
 * deletion. Once fine, always fine - the finger has said what it wants.
 *
 * Pure, with the caller holding [State] between events, so every rule here is checked without a
 * device. `PointerTracker` already owns equivalent state for the cursor slide beside this one.
 */
object BackspaceSlideMode {

    /**
     * @param anchorX where the next step is measured from; advances as steps are committed
     * @param startX where the finger went down, fixed for the gesture - escalation measures from
     *   here rather than from [anchorX], which moves
     * @param escalated whether this gesture has switched up to word granularity
     * @param latchedFine whether a reversal has pinned it to characters
     * @param lastStepSign direction of the last committed step, 0 before the first
     */
    data class State(
        val anchorX: Int,
        val startX: Int,
        val escalated: Boolean = false,
        val latchedFine: Boolean = false,
        val lastStepSign: Int = 0
    )

    /**
     * @param charStepPx travel per character step
     * @param wordStepPx travel per word step
     * @param escalateAfterPx travel from [State.startX] after which words take over
     * @param wordsAllowed false when the user asked for character deletion and nothing else
     */
    data class Config(
        val charStepPx: Int,
        val wordStepPx: Int,
        val escalateAfterPx: Int,
        val wordsAllowed: Boolean
    )

    /**
     * @param steps granularity-steps to apply now; zero until something crosses a threshold
     * @param wordMode whether those steps are words - what the IME needs in order to act on them
     */
    data class Result(val steps: Int, val wordMode: Boolean, val state: State)

    @JvmStatic
    fun next(x: Int, state: State, config: Config): Result {
        var escalated = state.escalated
        var latchedFine = state.latchedFine

        // Escalate before measuring, so the step that crosses the threshold is already a word step
        // rather than one last character step at the old granularity.
        if (config.wordsAllowed && !escalated && !latchedFine &&
            kotlin.math.abs(x - state.startX) >= config.escalateAfterPx) {
            escalated = true
        }

        var wordMode = escalated && !latchedFine && config.wordsAllowed
        var stepPx = if (wordMode) config.wordStepPx else config.charStepPx
        var steps = (x - state.anchorX) / stepPx

        // A reversal means the finger wants precision back. Only checked while coarse: once fine,
        // there is nothing to switch to, and a step of zero has no direction to compare.
        if (wordMode && steps != 0 && state.lastStepSign != 0) {
            val sign = if (steps > 0) 1 else -1
            if (sign != state.lastStepSign) {
                latchedFine = true
                wordMode = false
                // Re-measured finely from the same anchor, so the reversal is felt at the precision
                // it just asked for rather than in the units it was travelling in a moment ago.
                stepPx = config.charStepPx
                steps = (x - state.anchorX) / stepPx
            }
        }

        if (steps == 0) {
            return Result(0, wordMode,
                state.copy(escalated = escalated, latchedFine = latchedFine))
        }

        return Result(
            steps = steps,
            wordMode = wordMode,
            state = state.copy(
                anchorX = state.anchorX + steps * stepPx,
                escalated = escalated,
                latchedFine = latchedFine,
                lastStepSign = if (steps > 0) 1 else -1
            )
        )
    }
}
