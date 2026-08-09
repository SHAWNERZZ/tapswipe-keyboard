package org.futo.inputmethod.keyboard.internal

import android.os.SystemClock

/**
 * Typing speed for the current text field.
 *
 * Counts committed words rather than keystrokes, so a swiped word and a tapped one are measured the
 * same way - which is the point on this keyboard, where the same word can be produced either way and
 * a keystroke count would make swiping look artificially slow.
 *
 * "Words per minute" is the conventional five-characters-per-word definition, not literal word
 * count, so the number is comparable to every other typing-speed measure a person has seen.
 */
object WpmTracker {

    /** Characters per "word", the standard WPM convention. */
    private const val CHARS_PER_WORD = 5.0

    /**
     * A gap longer than this is treated as not typing, and excluded from elapsed time.
     *
     * Without it the number answers "how fast did you fill this field", which collapses toward zero
     * the moment someone stops to think - a message composed over two minutes with a thirty second
     * pause would read as a fraction of the speed actually achieved. Excluding idle makes it
     * "how fast do you type", which is what the number is for. The threshold is deliberately
     * generous: pausing mid-sentence to choose a word is still typing.
     */
    private const val IDLE_GAP_MS = 10_000L

    /** Below these, the estimate is noise - a single quick word implies an absurd rate. */
    private const val MIN_CHARS = 12
    private const val MIN_ACTIVE_MS = 2_000L

    private var firstInputMs = 0L
    private var lastInputMs = 0L
    private var idleMs = 0L
    private var chars = 0

    /** Starts over. Called when input begins in a field, so each field is measured on its own. */
    @JvmStatic
    @Synchronized
    fun reset() {
        firstInputMs = 0L
        lastInputMs = 0L
        idleMs = 0L
        chars = 0
    }

    /** Records committed text. Separators count - they are keystrokes like any other. */
    @JvmStatic
    @Synchronized
    fun onCharsProduced(count: Int) {
        if (count <= 0) return
        val now = SystemClock.uptimeMillis()

        if (firstInputMs == 0L) {
            firstInputMs = now
        } else if (now - lastInputMs > IDLE_GAP_MS) {
            idleMs += now - lastInputMs
        }

        lastInputMs = now
        chars += count
    }

    /**
     * @return words per minute, or null while there is too little to say anything meaningful.
     */
    @JvmStatic
    @Synchronized
    fun wpm(): Int? {
        if (chars < MIN_CHARS || firstInputMs == 0L) return null

        // Measured to the last keystroke, not to now: otherwise simply looking at the number would
        // make it fall, since time keeps passing while nothing is being typed.
        val activeMs = (lastInputMs - firstInputMs) - idleMs
        if (activeMs < MIN_ACTIVE_MS) return null

        val minutes = activeMs / 60_000.0
        val words = chars / CHARS_PER_WORD
        val wpm = (words / minutes).toInt()

        // A four-digit reading means the arithmetic found something pathological rather than a very
        // fast typist; better to show nothing than something absurd.
        return if (wpm in 1..999) wpm else null
    }
}
