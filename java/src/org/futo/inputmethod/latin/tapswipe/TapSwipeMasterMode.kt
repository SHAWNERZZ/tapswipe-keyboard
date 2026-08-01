package org.futo.inputmethod.latin.tapswipe

/**
 * Master Mode — letter keys render as dots instead of letters.
 *
 * Named after the equivalent mode in the original Nintype keyboard. The idea is that once you are
 * swiping and pecking by shape rather than reading each key, the letters are visual noise; removing
 * them makes the trajectory easier to see and discourages hunting for individual keys.
 *
 * Like [TapSwipePeckIndicator] this is a transient flag read at draw time rather than a DataStore
 * lookup, because it is consulted once per key per frame. It is kept in sync by a settings-flow
 * collector in `LatinIME`, which also invalidates the keyboard when it changes. Nothing here
 * triggers a theme rebuild.
 */
object TapSwipeMasterMode {
    /** Written on the UI thread from LatinIME's settings collector, read while drawing. */
    @JvmStatic
    @Volatile
    var enabled: Boolean = false
        private set

    /**
     * @return true if the value changed, meaning the caller should invalidate the keyboard.
     */
    @JvmStatic
    fun set(value: Boolean): Boolean {
        if (enabled == value) return false
        enabled = value
        return true
    }

    /**
     * Whether letter labels should currently be replaced with dots.
     *
     * Peck mode deliberately wins: a word being spelled out letter by letter is exactly when the
     * user needs to see the letters, so peck reveals them again (and adds key borders) for as long
     * as it is active.
     */
    @JvmStatic
    fun shouldHideLetters(): Boolean = enabled && !TapSwipePeckIndicator.active

    /** The glyph drawn in place of a letter. */
    const val DOT = "•"
}
