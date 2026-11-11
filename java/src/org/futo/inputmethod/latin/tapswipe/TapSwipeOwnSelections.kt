package org.futo.inputmethod.latin.tapswipe

/**
 * Cursor positions the keyboard itself produced while a TapSwipe word is open, so a late report of
 * one of them is not mistaken for the user moving the cursor.
 *
 * ## The defect this prevents
 *
 * Found by the typing simulator, 2026-11-11. Swipe `different`, then tap `t`:
 *
 * 1. The tap writes the literal `differentt`. The cursor goes from 27 to 28.
 * 2. The decode lands and rewrites the word to `different`. The cursor goes back to 27, and the
 *    keyboard now expects 27.
 * 3. The editor's report of step 1 arrives late: 27 to 28. It does not match the expected 27, so
 *    `onUpdateSelection` read it as the user moving the cursor out of the word, and reset the input
 *    state. The word session died mid-word.
 *
 * The next tap then started a new word glued to the old one (`rememberor`, `somethingg`), or the
 * word landed without a commit. One backspace reopened the stray tail and left the intended word,
 * which is how the user described it.
 *
 * `isBelatedExpectedUpdate` only knows the newest expected position. This remembers the recent ones.
 *
 * ## Seam
 *
 * - Evidence: selection reports from the editor. Derived result: "the user moved the cursor".
 * - Invariant: a report means the user moved the cursor only if it names a position the keyboard
 *   did not produce itself.
 * - Window: between the keyboard's own write and the editor's report of it, a later own write can
 *   change the expected position. Reports for every own write still in flight must be recognised,
 *   not just the newest. Positions expire after [MAX_AGE_MS], so a real cursor move later on is
 *   never swallowed.
 */
class TapSwipeOwnSelections {
    private val positions = IntArray(CAPACITY)
    private val times = LongArray(CAPACITY)
    private var size = 0

    /** Notes the cursor position one of the keyboard's own writes produced. */
    fun note(selStart: Int, nowMs: Long) {
        if (size == CAPACITY) {
            System.arraycopy(positions, 1, positions, 0, CAPACITY - 1)
            System.arraycopy(times, 1, times, 0, CAPACITY - 1)
            size--
        }
        positions[size] = selStart
        times[size] = nowMs
        size++
    }

    /**
     * @return true if [reported] is a recent own position that is no longer the expected one, so the
     * report is stale. The match and everything older are consumed, since reports arrive in order.
     */
    fun consumeIfStale(reported: Int, expected: Int, nowMs: Long): Boolean {
        if (reported == expected) return false
        for (i in size - 1 downTo 0) {
            if (nowMs - times[i] > MAX_AGE_MS) continue
            if (positions[i] == reported) {
                val keep = size - (i + 1)
                System.arraycopy(positions, i + 1, positions, 0, keep)
                System.arraycopy(times, i + 1, times, 0, keep)
                size = keep
                return true
            }
        }
        return false
    }

    fun clear() {
        size = 0
    }

    companion object {
        const val CAPACITY = 8
        /** Editor reports arrive within tens of milliseconds. A second is far beyond that. */
        const val MAX_AGE_MS = 1000L
    }
}
