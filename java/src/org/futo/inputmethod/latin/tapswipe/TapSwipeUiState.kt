package org.futo.inputmethod.latin.tapswipe

/**
 * Which of TapSwipe's input modes the word currently being composed is in.
 *
 * Decided once per word and then latched, so the keyboard's appearance cannot flicker mid-word.
 * A swipe always forces back to [SWIPE], which is also how legacy-tap mode is left again.
 */
enum class TapSwipeMode {
    /** The word contains a swipe. The decoded candidate is authoritative and never autocorrected. */
    SWIPE,

    /** Too few taps so far to classify a swipe-free word. Behaves like stock typing. */
    UNDECIDED,

    /**
     * A swipe-free word spelled out deliberately (slow cadence). No autocorrect, committed
     * verbatim, learned afterwards, no morphing. Letters and key borders are shown.
     */
    PECK,

    /**
     * A swipe-free word typed fluently (fast cadence). Ordinary typing: autocorrect and key
     * boosting behave exactly as upstream. Letters are shown, but no borders.
     */
    LEGACY_TAP
}

/**
 * Draw-time view of the current mode, driving key borders and Master Mode's dots.
 *
 * Deliberately *not* the persisted [org.futo.inputmethod.latin.uix.KeyBordersSetting]: writing that
 * would clobber the user's own preference, and every change rebuilds the whole drawable provider
 * (see `LatinIME.kt`) - far too heavy to run twice per word. Instead `BasicThemeProvider`
 * precomputes bordered variants of the four styles that depend on key borders and swaps to them at
 * draw time. Borders have no effect if the user already has key borders switched on.
 *
 * Held as a transient flag rather than read from DataStore because it is consulted once per key per
 * frame. `InputLogic` keeps it in sync and repaints via `invalidateAllKeys()`.
 */
object TapSwipeUiState {
    /** Written on the UI thread from InputLogic, read while drawing. */
    @JvmStatic
    @Volatile
    var mode: TapSwipeMode = TapSwipeMode.SWIPE
        private set

    /**
     * @return true if the mode changed, meaning the caller should invalidate the keyboard.
     */
    @JvmStatic
    fun set(value: TapSwipeMode): Boolean {
        if (mode == value) return false
        mode = value
        return true
    }

    /** Key borders mark peck mode, where the user is deliberately spelling something out. */
    @JvmStatic
    fun showBorders(): Boolean = mode == TapSwipeMode.PECK

    /**
     * Whether letters must be readable regardless of Master Mode. True whenever the word is being
     * tapped out rather than swiped - which is exactly when the letters are needed.
     */
    @JvmStatic
    fun showLetters(): Boolean =
        mode == TapSwipeMode.PECK || mode == TapSwipeMode.LEGACY_TAP
}
